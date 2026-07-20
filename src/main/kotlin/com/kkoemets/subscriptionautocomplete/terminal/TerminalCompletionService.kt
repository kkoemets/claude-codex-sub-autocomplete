package com.kkoemets.subscriptionautocomplete.terminal

import com.kkoemets.subscriptionautocomplete.completion.CompletionCandidateSource
import com.kkoemets.subscriptionautocomplete.completion.CompletionPipelineEvent
import com.kkoemets.subscriptionautocomplete.completion.CompletionPipelineStage
import com.kkoemets.subscriptionautocomplete.completion.CompletionRuntimeState
import com.kkoemets.subscriptionautocomplete.completion.CompletionSurface
import com.kkoemets.subscriptionautocomplete.completion.CompletionTerminalReason
import com.kkoemets.subscriptionautocomplete.completion.FailureNotifier
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog
import com.kkoemets.subscriptionautocomplete.provider.BackendRegistry
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.view.shellIntegration.getTypedCommandText

@Suppress("UnstableApiUsage")
@Service(Service.Level.PROJECT)
class TerminalCompletionService(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
) {
  private val running = AtomicBoolean()

  fun request(terminal: TerminalView) {
    request(
      capture = { capture(terminal) },
      sendText = { terminal.sendText(it) },
    )
  }

  fun request(terminal: TerminalWidget) {
    request(
      capture = { capture(terminal) },
      sendText = { sendText(terminal, it) },
    )
  }

  private fun request(
    capture: () -> TerminalCommandCapture?,
    sendText: (String) -> Unit,
  ) {
    val settings = AutocompleteSettings.getInstance().snapshot()
    if (!settings.enabled || !settings.terminalCompletionsEnabled || !running.compareAndSet(false, true)) {
      sendText("\t")
      return
    }
    val captured = capture()
    if (captured == null) {
      running.set(false)
      sendText("\t")
      return
    }
    coroutineScope.launch {
      try {
        generate(captured, capture, sendText, settings)
      } finally {
        running.set(false)
      }
    }
  }

  fun isRunning(): Boolean = running.get()

  private suspend fun generate(
    captured: TerminalCommandCapture,
    capture: () -> TerminalCommandCapture?,
    sendText: (String) -> Unit,
    settings: AutocompleteSettings.SettingsState,
  ) {
    val runtime = CompletionRuntimeState.getInstance(project)
    val diagnostics = DiagnosticsLog.getInstance()
    val requestId = runtime.nextRequestId()
    val startedAt = System.nanoTime()
    val provider = settings.selectedProvider()
    observe(runtime, requestId, CompletionPipelineStage.TRIGGERED, startedAt, provider)
    diagnostics.info(
      "Terminal command #$requestId started",
      "Provider: ${provider.displayName}; shell: ${captured.shell}; " +
        "project markers: ${captured.context.projectMarkers.size}",
    )
    try {
      observe(runtime, requestId, CompletionPipelineStage.CONTEXT_READY, startedAt, provider)
      val prompt = TerminalCommandPromptBuilder.build(captured.context)
      val requestSettings = settings.copy(maxOutputTokens = minOf(settings.maxOutputTokens, TERMINAL_OUTPUT_TOKENS))
      observe(runtime, requestId, CompletionPipelineStage.BACKEND_STARTED, startedAt, provider)
      when (val result = BackendRegistry.forProvider(provider).complete(prompt, requestSettings)) {
        is BackendResult.Failure -> {
          observe(runtime, requestId, CompletionPipelineStage.BACKEND_FINISHED, startedAt, provider)
          diagnostics.warning(
            "Terminal command #$requestId failed",
            "${elapsedMillis(startedAt)} ms; ${result.message}",
          )
          observe(
            runtime,
            requestId,
            CompletionPipelineStage.FAILED,
            startedAt,
            provider,
            result.message.failureReason(),
          )
          FailureNotifier.notify(project, result.message)
        }
        is BackendResult.Success -> {
          observe(runtime, requestId, CompletionPipelineStage.BACKEND_FINISHED, startedAt, provider)
          val command = TerminalCommandSanitizer.sanitize(result.text, requestSettings.maxOutputTokens)
          observe(runtime, requestId, CompletionPipelineStage.SANITIZED, startedAt, provider)
          if (command.isBlank()) {
            diagnostics.warning(
              "Terminal command #$requestId rejected",
              "Provider output was blank, explanatory, multiline, or contained terminal control data.",
            )
            observe(
              runtime,
              requestId,
              CompletionPipelineStage.NO_RESULT,
              startedAt,
              provider,
              CompletionTerminalReason.UNSAFE_OUTPUT,
            )
            return
          }
          observe(runtime, requestId, CompletionPipelineStage.VALIDATED, startedAt, provider)
          val latestSettings = AutocompleteSettings.getInstance().snapshot()
          if (
            !latestSettings.enabled ||
            !latestSettings.terminalCompletionsEnabled ||
            latestSettings.settingsRevision != settings.settingsRevision
          ) {
            diagnostics.info(
              "Terminal command #$requestId discarded",
              "Plugin settings changed before the provider returned; the typed request was preserved.",
            )
            observe(
              runtime,
              requestId,
              CompletionPipelineStage.STALE_REJECTED,
              startedAt,
              provider,
              CompletionTerminalReason.STALE,
            )
            return
          }
          val current = capture()
          if (current?.typedCommand != captured.typedCommand) {
            diagnostics.info(
              "Terminal command #$requestId discarded",
              "The terminal input changed before the provider returned; the typed request was preserved.",
            )
            observe(
              runtime,
              requestId,
              CompletionPipelineStage.STALE_REJECTED,
              startedAt,
              provider,
              CompletionTerminalReason.STALE,
            )
            return
          }
          sendText("\u0015$command")
          diagnostics.info(
            "Terminal command #$requestId inserted",
            "${elapsedMillis(startedAt)} ms; model: ${result.model}; transport: " +
              result.transport.ifBlank { "provider default" } + "; output: ${command.length} characters; not executed",
          )
          observe(runtime, requestId, CompletionPipelineStage.RENDER_READY, startedAt, provider)
        }
      }
    } catch (cancelled: CancellationException) {
      diagnostics.info("Terminal command #$requestId cancelled", "${elapsedMillis(startedAt)} ms")
      observe(
        runtime,
        requestId,
        CompletionPipelineStage.CANCELLED,
        startedAt,
        provider,
        CompletionTerminalReason.CANCELLED,
      )
      throw cancelled
    } catch (error: Exception) {
      val message = error.message ?: error.javaClass.simpleName
      diagnostics.error(
        "Terminal command #$requestId crashed",
        "${elapsedMillis(startedAt)} ms; ${error.javaClass.name}: $message",
      )
      observe(runtime, requestId, CompletionPipelineStage.FAILED, startedAt, provider)
      FailureNotifier.notify(project, "Terminal command generation failed: $message")
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun capture(terminal: TerminalView): TerminalCommandCapture? {
    val integration = terminal.shellIntegrationDeferred.completedOrNull()
    val typedCommand = integration
      ?.takeIf { it.outputStatus.value is TerminalOutputStatus.TypingCommand }
      ?.blocksModel
      ?.activeBlock
      ?.let { it as? TerminalCommandBlock }
      ?.let { block -> runCatching { block.getTypedCommandText(terminal.outputModels.regular) }.getOrNull() }
    val directDescription = typedCommand?.let { TerminalCommandTrigger.extract(it) }
    val renderedLine = if (directDescription == null) currentRenderedLine(terminal) else null
    val description = directDescription
      ?: renderedLine?.let { TerminalCommandTrigger.extractFromRenderedLine(it) }
      ?: return null
    val capturedLine = typedCommand.takeIf { directDescription != null } ?: renderedLine ?: return null
    val startupOptions = terminal.startupOptionsDeferred.completedOrNull()
    val context = TerminalProjectContextCollector.collect(
      description = description,
      shellCommand = startupOptions?.shellCommand.orEmpty(),
      currentDirectory = terminal.getCurrentDirectory(),
      projectName = project.name,
      projectBasePath = project.basePath,
    )
    return TerminalCommandCapture(capturedLine, context.shell, context)
  }

  private fun capture(terminal: TerminalWidget): TerminalCommandCapture? {
    val renderedLine = runCatching { TerminalRenderedText.currentLine(terminal.getText()) }.getOrNull() ?: return null
    val description = TerminalCommandTrigger.extractFromRenderedLine(renderedLine) ?: return null
    val context = TerminalProjectContextCollector.collect(
      description = description,
      shellCommand = runCatching { terminal.shellCommand?.toList().orEmpty() }.getOrElse { emptyList() },
      currentDirectory = runCatching<String?> { terminal.getCurrentDirectory() }.getOrNull(),
      projectName = project.name,
      projectBasePath = project.basePath,
    )
    return TerminalCommandCapture(renderedLine, context.shell, context)
  }

  private fun sendText(terminal: TerminalWidget, text: String) {
    runCatching {
      terminal.ttyConnectorAccessor.executeWithTtyConnector { connector ->
        runCatching { connector.write(text) }
          .onFailure { error ->
            DiagnosticsLog.getInstance().warning(
              "Terminal input forwarding failed",
              "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
          }
      }
    }.onFailure { error ->
      DiagnosticsLog.getInstance().warning(
        "Terminal input forwarding failed",
        "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
      )
    }
  }

  private fun currentRenderedLine(terminal: TerminalView): String? {
    val models = listOf(terminal.outputModels.active.value, terminal.outputModels.regular).distinct()
    return models.firstNotNullOfOrNull { model ->
      runCatching {
        val snapshot = model.takeSnapshot()
        if (snapshot.lineCount == 0) return@runCatching null
        var lineIndex = snapshot.lastLineIndex
        repeat(minOf(snapshot.lineCount, MAX_RENDERED_LINES_TO_SCAN)) { offset ->
          val line = snapshot.getText(
            snapshot.getStartOfLine(lineIndex),
            snapshot.getEndOfLine(lineIndex, true),
          ).toString().trimEnd()
          if (line.isNotBlank()) return@runCatching line
          if (offset + 1 < snapshot.lineCount) lineIndex = lineIndex.minus(1)
        }
        null
      }.getOrNull()
    }
  }

  private fun observe(
    runtime: CompletionRuntimeState,
    requestId: Long,
    stage: CompletionPipelineStage,
    startedAt: Long,
    provider: com.kkoemets.subscriptionautocomplete.settings.ProviderKind,
    terminalReason: CompletionTerminalReason? = null,
  ) {
    runtime.observe(
      CompletionPipelineEvent(
        requestId = requestId,
        stage = stage,
        elapsedMillis = elapsedMillis(startedAt),
        source = CompletionCandidateSource.PROVIDER,
        provider = provider,
        terminalReason = terminalReason,
        surface = CompletionSurface.TERMINAL,
      ),
    )
  }

  private fun String.failureReason(): CompletionTerminalReason = when {
    contains("timeout", ignoreCase = true) || contains("timed out", ignoreCase = true) ->
      CompletionTerminalReason.TIMEOUT
    contains("output safety envelope", ignoreCase = true) -> CompletionTerminalReason.OUTPUT_LIMIT
    else -> CompletionTerminalReason.PROVIDER_FAILURE
  }

  private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun <T> kotlinx.coroutines.Deferred<T>.completedOrNull(): T? =
    if (!isCompleted || isCancelled) null else runCatching { getCompleted() }.getOrNull()

  private data class TerminalCommandCapture(
    val typedCommand: String,
    val shell: String,
    val context: TerminalPromptContext,
  )

  companion object {
    private const val MAX_RENDERED_LINES_TO_SCAN = 40

    fun getInstance(project: Project): TerminalCompletionService = project.getService(TerminalCompletionService::class.java)
  }
}
