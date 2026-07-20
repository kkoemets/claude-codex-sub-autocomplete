package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.context.ContextEngine
import com.kkoemets.subscriptionautocomplete.context.CompletionContext
import com.kkoemets.subscriptionautocomplete.context.ContextSharingPolicy
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog
import com.kkoemets.subscriptionautocomplete.provider.BackendRegistry
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.BackendCompletionRequest
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.provider.CompletionRequestSnapshot
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.kkoemets.subscriptionautocomplete.settings.SyntaxValidationMode
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SubscriptionCompletionProvider private constructor(
  private val engineResolver: CompletionEngineResolver,
  private val backendLookup: (ResolvedCompletionEngine) -> CompletionBackend?,
  private val validatorLookup: (com.intellij.openapi.project.Project) -> CompletionResultValidator,
  private val observer: CompletionPipelineObserver,
) : DebouncedInlineCompletionProvider() {
  constructor() : this(
    CompletionEngineResolver(),
    BackendRegistry::forEngine,
    PsiSyntaxCompletionValidator::getInstance,
    CompletionPipelineObserver.NONE,
  )

  internal constructor(
    engineResolver: CompletionEngineResolver,
    backendLookup: (ResolvedCompletionEngine) -> CompletionBackend?,
    validatorLookup: (com.intellij.openapi.project.Project) -> CompletionResultValidator =
      PsiSyntaxCompletionValidator::getInstance,
    observer: CompletionPipelineObserver = CompletionPipelineObserver.NONE,
    @Suppress("UNUSED_PARAMETER") injected: Unit = Unit,
  ) : this(engineResolver, backendLookup, validatorLookup, observer)

  override val id = InlineCompletionProviderID("SubscriptionAutocomplete")

  override fun isEnabled(event: InlineCompletionEvent): Boolean {
    if (event !is InlineCompletionEvent.DirectCall && event !is InlineCompletionEvent.DocumentChange) return false
    val settings = AutocompleteSettings.getInstance().snapshot()
    if (!settings.enabled) return false
    val mode = if (event.isManualCompletion()) CompletionMode.MANUAL else CompletionMode.AUTOMATIC
    val engine = engineResolver.resolve(mode, settings) ?: return false
    return engine.provider != null
  }

  override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
    if (!request.event.isManualCompletion()) {
      cachedSuggestion(request)?.let { return it }
    }
    return super.getSuggestion(request)
  }

  override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
    val project = request.editor.project ?: return 0.milliseconds
    val settings = AutocompleteSettings.getInstance().snapshot()
    val engine = engineResolver.resolve(CompletionMode.AUTOMATIC, settings) ?: return 0.milliseconds
    val typed = request.typedText()
    return CompletionRuntimeState.getInstance(project).pacing.debounceMillis(
      request.pacingKey(engine),
      settings.debounceMs,
      CompletionTriggerPolicy.isFastBoundary(typed),
    ).milliseconds
  }

  override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
    val project = request.editor.project ?: return EMPTY_SUGGESTION
    val settingsService = AutocompleteSettings.getInstance()
    val settings = settingsService.snapshot()
    val mode = if (request.event.isManualCompletion()) CompletionMode.MANUAL else CompletionMode.AUTOMATIC
    val engine = engineResolver.resolve(mode, settings) ?: return EMPTY_SUGGESTION
    val provider = engine.provider ?: return EMPTY_SUGGESTION
    val runtime = CompletionRuntimeState.getInstance(project)
    val pacingKey = request.pacingKey(engine)
    if (mode == CompletionMode.AUTOMATIC) {
      val shouldRequest = readAction {
        CompletionTriggerPolicy.shouldRequest(
          request.document.charsSequence,
          request.endOffset,
          request.typedText(),
        )
      }
      if (!shouldRequest) {
        DiagnosticsLog.getInstance().infoCoalesced(
          "automatic-skip:${request.file.virtualFile?.path ?: request.file.name}",
          "Automatic completion skipped",
          "Trigger gate found no useful completion boundary in ${request.file.name}.",
        )
        return EMPTY_SUGGESTION
      }
    }
    val diagnostics = DiagnosticsLog.getInstance()
    val requestId = runtime.nextRequestId()
    val startedAt = System.nanoTime()
    observe(runtime, requestId, CompletionPipelineStage.TRIGGERED, startedAt, provider = provider)
    val completionIntent = if (mode == CompletionMode.AUTOMATIC && request.typedText().isExactNewline()) {
      readAction {
        val classified = CompletionIntentClassifier.classify(
          request.document.charsSequence,
          request.endOffset,
          mode,
          request.file.language.id,
        )
        if (
          classified.isImplementation() &&
          isPsiLineCommentIntent(request.file, request.document.charsSequence, request.endOffset)
        ) classified else CompletionIntent.ORDINARY
      }
    } else {
      CompletionIntent.ORDINARY
    }
    val effectiveContextBudget = CompletionLimits.contextTokens(
      settings.contextTokenBudget,
      mode,
      completionIntent,
    )
    val effectiveOutputTokens = CompletionLimits.outputTokens(
      settings.maxOutputTokens,
      mode,
      completionIntent,
    )
    val requestSettings = settings.copy(
      contextTokenBudget = effectiveContextBudget,
      maxOutputTokens = effectiveOutputTokens,
    )
    diagnostics.info(
      "Completion #$requestId started",
      "Mode: ${mode.name.lowercase()}; provider: ${provider.displayName}; " +
        "model: ${modelName(provider, settings)}; intent: ${completionIntent.value}; context/output budget: " +
        "$effectiveContextBudget/$effectiveOutputTokens tokens",
    )
    return try {
      val cacheAnchor = readAction {
        runtime.suggestionCache.capture(request.document.charsSequence, request.endOffset)
      }
      val contextStartedAt = System.nanoTime()
      val context = ContextEngine.getInstance(project).gather(
        request.editor,
        request.endOffset,
        effectiveContextBudget,
        ContextSharingPolicy.from(engine.destination, settings),
        mode,
      )
      if (context == null) {
        diagnostics.warning("Completion #$requestId skipped", "No PSI file is available for this editor.")
        observe(runtime, requestId, CompletionPipelineStage.NO_RESULT, startedAt)
        return EMPTY_SUGGESTION
      }
      val contextMillis = elapsedMillis(contextStartedAt)
      observe(runtime, requestId, CompletionPipelineStage.CONTEXT_READY, startedAt)
      diagnostics.info(
        "Completion #$requestId context ready",
        "File: ${context.fileName}; language: ${context.languageId}; " +
          "estimated input: ~${estimatedContextUnits(context)} model units; " +
          "semantic fragments: ${context.fragments.size}; semantic cache: " +
          (if (context.semanticCacheHit) "hit" else "miss") + "; context: $contextMillis ms",
      )
      val prompt = CompletionPromptBuilder.build(context, mode, completionIntent)
      val providerStartedAt = System.nanoTime()
      val backend = backendLookup(engine)
      if (backend == null) {
        observe(runtime, requestId, CompletionPipelineStage.NO_RESULT, startedAt)
        return EMPTY_SUGGESTION
      }
      observe(runtime, requestId, CompletionPipelineStage.BACKEND_STARTED, startedAt)
      val backendRequest = BackendCompletionRequest(
        prompt = prompt,
        context = context,
        mode = mode,
        requestSnapshot = CompletionRequestSnapshot(
          document = context.documentSnapshot,
          engine = engine.id,
          settingsRevision = settings.settingsRevision,
          contextDependencies = context.dependencyFingerprint,
        ),
      )
      when (val result = backend.complete(backendRequest, requestSettings)) {
        is BackendResult.Failure -> {
          runtime.pacing.recordTerminalResult(pacingKey)
          observe(runtime, requestId, CompletionPipelineStage.BACKEND_FINISHED, startedAt)
          val providerMillis = elapsedMillis(providerStartedAt)
          diagnostics.warning(
            "Completion #$requestId failed",
            "${elapsedMillis(startedAt)} ms; context: $contextMillis ms; provider: $providerMillis ms; " +
              result.message,
          )
          observe(
            runtime,
            requestId,
            CompletionPipelineStage.FAILED,
            startedAt,
            terminalReason = result.message.failureReason(),
          )
          FailureNotifier.notify(project, result.message)
          EMPTY_SUGGESTION
        }
        is BackendResult.Success -> {
          runtime.pacing.recordTerminalResult(pacingKey)
          observe(runtime, requestId, CompletionPipelineStage.BACKEND_FINISHED, startedAt)
          val providerMillis = elapsedMillis(providerStartedAt)
          val sanitizeStartedAt = System.nanoTime()
          val completion = CompletionSanitizer.sanitize(
            result.text,
            context.prefix,
            context.suffix,
            effectiveOutputTokens,
            context.languageId,
            completionIntent,
          )
          val sanitizeMillis = elapsedMillis(sanitizeStartedAt)
          observe(runtime, requestId, CompletionPipelineStage.SANITIZED, startedAt)
          if (completion.isBlank()) {
            diagnostics.warning(
              "Completion #$requestId returned no insertable text",
              "${elapsedMillis(startedAt)} ms; model: ${result.model}",
            )
            observe(
              runtime,
              requestId,
              CompletionPipelineStage.NO_RESULT,
              startedAt,
              terminalReason = CompletionTerminalReason.BLANK,
            )
            EMPTY_SUGGESTION
          } else {
            if (!requestIsCurrent(request, context, settings.settingsRevision)) {
              diagnostics.info("Completion #$requestId discarded", "The document or settings changed before render.")
              observe(runtime, requestId, CompletionPipelineStage.STALE_REJECTED, startedAt)
              return EMPTY_SUGGESTION
            }
            val validation = validateResult(project, request, context, completion, settings, completionIntent)
            observe(runtime, requestId, CompletionPipelineStage.VALIDATED, startedAt)
            if (
              validation is CompletionValidation.Reject &&
              (
                completionIntent.isImplementation() ||
                  settings.selectedSyntaxValidationMode() == SyntaxValidationMode.ENFORCE && validation.enforceable
              )
            ) {
              diagnostics.info(
                "Completion #$requestId rejected",
                "Syntax validation rejected ${validation.errors.size} new parser error(s).",
              )
              observe(
                runtime,
                requestId,
                CompletionPipelineStage.NO_RESULT,
                startedAt,
                terminalReason = CompletionTerminalReason.SYNTAX_REJECTED,
              )
              return EMPTY_SUGGESTION
            }
            if (validation is CompletionValidation.Reject) {
              diagnostics.info(
                "Completion #$requestId syntax shadow",
                "Would reject ${validation.errors.size} new parser error(s); enforcement is disabled for this parser/build.",
              )
            }
            if (!requestIsCurrent(request, context, settings.settingsRevision)) {
              diagnostics.info("Completion #$requestId discarded", "The document or settings changed during validation.")
              observe(runtime, requestId, CompletionPipelineStage.STALE_REJECTED, startedAt)
              return EMPTY_SUGGESTION
            }
            diagnostics.info(
              "Completion #$requestId ready",
              "${elapsedMillis(startedAt)} ms; model: ${result.model}; transport: " +
                "${result.transport.ifBlank { "provider default" }}; context/provider/sanitize: " +
                "$contextMillis/$providerMillis/$sanitizeMillis ms; first token: " +
                "${result.timing.firstTokenMillis?.let { "$it ms" } ?: "unavailable"}; " +
                "output: ${completion.length} characters",
            )
            if (!context.dependencyFingerprint.hasCrossFileContent) {
              runtime.suggestionCache.put(
                cacheKey(request, engine, provider, settings),
                cacheAnchor,
                completion,
              )
            }
            observe(runtime, requestId, CompletionPipelineStage.RENDER_READY, startedAt)
            suggestion(completion)
          }
        }
      }
    } catch (cancelled: CancellationException) {
      if (mode == CompletionMode.AUTOMATIC) runtime.pacing.recordCancellation(pacingKey)
      observe(runtime, requestId, CompletionPipelineStage.CANCELLED, startedAt)
      diagnostics.info("Completion #$requestId cancelled", "${elapsedMillis(startedAt)} ms")
      throw cancelled
    } catch (error: Exception) {
      val message = error.message ?: error.javaClass.simpleName
      val trace = error.stackTrace.take(8).joinToString("\n") { "at $it" }
      diagnostics.error(
        "Completion #$requestId crashed",
        "${elapsedMillis(startedAt)} ms; ${error.javaClass.name}: $message\n$trace",
      )
      observe(runtime, requestId, CompletionPipelineStage.FAILED, startedAt)
      FailureNotifier.notify(project, "Subscription autocomplete failed: $message")
      EMPTY_SUGGESTION
    }
  }

  companion object {
    private val EMPTY_SUGGESTION = InlineCompletionSingleSuggestion.build { _ -> }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun modelName(
      provider: ProviderKind,
      settings: AutocompleteSettings.SettingsState,
    ): String = when (provider) {
      ProviderKind.CLAUDE -> settings.claudeModel
      ProviderKind.CODEX -> settings.codexModel
    }

    private fun suggestion(text: String): InlineCompletionSuggestion =
      InlineCompletionSingleSuggestion.build { _ -> emit(InlineCompletionGrayTextElement(text)) }

    private fun cacheKey(
      request: InlineCompletionRequest,
      engine: ResolvedCompletionEngine,
      provider: ProviderKind,
      settings: AutocompleteSettings.SettingsState,
    ): SuggestionCacheKey = SuggestionCacheKey(
      filePath = request.file.virtualFile?.path ?: request.file.name,
      engine = engine.id,
      model = modelName(provider, settings),
      settingsRevision = settings.settingsRevision,
      contextSharingRevision = ContextSharingPolicy.from(engine.destination, settings).revision,
    )
  }

  private suspend fun cachedSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion? {
    val project = request.editor.project ?: return null
    val settingsService = AutocompleteSettings.getInstance()
    val settings = settingsService.snapshot()
    val engine = engineResolver.resolve(CompletionMode.AUTOMATIC, settings) ?: return null
    val provider = engine.provider ?: return null
    val runtime = CompletionRuntimeState.getInstance(project)
    val remaining = readAction {
      runtime.suggestionCache.remaining(
        cacheKey(request, engine, provider, settings),
        request.document.charsSequence,
        request.endOffset,
      )
    } ?: return null
    DiagnosticsLog.getInstance().infoCoalesced(
      "completion-cache:${request.file.virtualFile?.path ?: request.file.name}",
      "Completion cache hit",
      "Reused ${remaining.length} characters for ${request.file.name}; provider request avoided.",
    )
    val requestId = runtime.nextRequestId()
    val event = CompletionPipelineEvent(
      requestId = requestId,
      stage = CompletionPipelineStage.RENDER_READY,
      elapsedMillis = 0,
      source = CompletionCandidateSource.CACHE,
      provider = provider,
      terminalReason = CompletionTerminalReason.READY,
    )
    runtime.observe(event)
    observer.onEvent(event)
    return suggestion(remaining)
  }

  private suspend fun requestIsCurrent(
    request: InlineCompletionRequest,
    context: CompletionContext,
    settingsRevision: Long,
  ): Boolean {
    if (AutocompleteSettings.getInstance().state.settingsRevision != settingsRevision) return false
    val snapshot = context.documentSnapshot ?: return false
    return readAction {
      request.document.modificationStamp == snapshot.modificationStamp &&
        request.editor.caretModel.offset == snapshot.caretOffset
    }
  }

  private suspend fun validateResult(
    project: com.intellij.openapi.project.Project,
    request: InlineCompletionRequest,
    context: CompletionContext,
    completion: String,
    settings: AutocompleteSettings.SettingsState,
    intent: CompletionIntent,
  ): CompletionValidation {
    if (
      settings.selectedSyntaxValidationMode() == SyntaxValidationMode.OFF &&
      !intent.isImplementation()
    ) {
      return CompletionValidation.Skipped("disabled")
    }
    val document = context.documentSnapshot ?: return CompletionValidation.Skipped("missing document snapshot")
    return validatorLookup(project).validate(
      CompletionValidationRequest(
        fileName = request.file.name,
        fileType = request.file.fileType,
        languageId = context.languageId,
        document = document,
        completion = completion,
      ),
    )
  }

  private fun observe(
    runtime: CompletionRuntimeState,
    requestId: Long,
    stage: CompletionPipelineStage,
    startedAt: Long,
    source: CompletionCandidateSource = CompletionCandidateSource.PROVIDER,
    provider: ProviderKind? = null,
    terminalReason: CompletionTerminalReason? = null,
  ) {
    val event = CompletionPipelineEvent(
      requestId = requestId,
      stage = stage,
      elapsedMillis = elapsedMillis(startedAt),
      source = source,
      provider = provider,
      terminalReason = terminalReason,
    )
    runtime.observe(event)
    observer.onEvent(event)
  }
}

private fun String.failureReason(): CompletionTerminalReason =
  if (contains("timeout", ignoreCase = true) || contains("timed out", ignoreCase = true)) {
    CompletionTerminalReason.TIMEOUT
  } else if (contains("output safety envelope", ignoreCase = true)) {
    CompletionTerminalReason.OUTPUT_LIMIT
  } else {
    CompletionTerminalReason.PROVIDER_FAILURE
  }

private fun estimatedContextUnits(context: CompletionContext): Int {
  val characters = context.prefix.length + context.suffix.length + context.fragments.sumOf { fragment ->
    fragment.label.length + fragment.content.length + 8
  }
  return maxOf(1, (characters + 3) / 4)
}

private fun InlineCompletionEvent.isManualCompletion(): Boolean = this is InlineCompletionEvent.DirectCall

private fun InlineCompletionRequest.typedText(): String =
  (event as? InlineCompletionEvent.DocumentChange)?.typing?.typed.orEmpty()

private fun String.isExactNewline(): Boolean = this == "\n" || this == "\r\n"

internal fun isPsiLineCommentIntent(file: PsiFile, text: CharSequence, endOffset: Int): Boolean {
  val prefix = when (file.language.id.lowercase()) {
    "typescript", "javascript", "java", "kotlin" -> "//"
    "python" -> "#"
    else -> return false
  }
  val offset = endOffset.coerceIn(0, text.length)
  val currentLineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
  if (currentLineStart <= 0) return false
  var lineEnd = currentLineStart
  var commentLines = 0
  while (lineEnd > 0 && commentLines < MAX_PSI_INTENT_LINES) {
    val lineStart = text.lastIndexOf('\n', lineEnd - 1).let { index -> if (index < 0) 0 else index + 1 }
    val commentOffset = (lineStart until lineEnd).firstOrNull { index -> !text[index].isWhitespace() }
      ?: break
    if (!text.subSequence(commentOffset, lineEnd).startsWith(prefix)) break
    val comment = generateSequence(file.findElementAt(commentOffset)) { element -> element.parent }
      .filterIsInstance<PsiComment>()
      .firstOrNull()
      ?: return false
    if (comment.textRange.startOffset != commentOffset || !comment.text.startsWith(prefix)) return false
    commentLines += 1
    if (lineStart == 0) break
    lineEnd = lineStart - 1
  }
  return commentLines > 0
}

private const val MAX_PSI_INTENT_LINES = 6

private fun InlineCompletionRequest.pacingKey(engine: ResolvedCompletionEngine): String =
  "${engine.id}:${file.virtualFile?.path ?: file.name}"
