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
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Suppress("UnstableApiUsage")
@Service(Service.Level.PROJECT)
class TerminalCompletionService(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
) : Disposable {
  private val pending = AtomicReference<PendingTerminalRequest?>()
  private val inputRevision = AtomicLong()

  fun canRequest(terminal: TerminalView): Boolean = ReworkedTerminalCompletionTarget.readInput(terminal) != null

  fun canRequest(terminal: TerminalWidget): Boolean = ClassicTerminalCompletionTarget.readInput(terminal) != null

  fun request(terminal: TerminalView): Boolean =
    request(ReworkedTerminalCompletionTarget(terminal, inputRevision, project.name, project.basePath))

  fun request(terminal: TerminalWidget, forwardTabIfBusy: Boolean = false): Boolean =
    request(
      ClassicTerminalCompletionTarget(terminal, inputRevision, project.name, project.basePath),
      forwardTabIfBusy,
    )

  /** Called before native input can submit or change the command, even if the echo is delayed. */
  internal fun inputChanged() {
    pending.get()?.let { request ->
      if (request.probing) {
        debug { "inputChanged cancels probe target=${request.target.debugIdentity()} tabs=${request.tabs} input=${request.input.debugSummary()}" }
        // Queue provisional native Tabs before the later key reaches the terminal input queue.
        request.forwardTabs()
        request.cancelled = true
      }
    }
    inputRevision.incrementAndGet()
  }

  internal fun request(
    target: TerminalCompletionTarget,
    forwardTabIfBusy: Boolean = false,
    backend: (ProviderKind) -> CompletionBackend = BackendRegistry::forProvider,
  ): Boolean {
    debug { "request entered target=${target.debugIdentity()} forwardTabIfBusy=$forwardTabIfBusy" }
    val settings = AutocompleteSettings.getInstance().snapshot()
    if (!settings.enabled || !settings.terminalCompletionsEnabled) {
      debug { "request rejected settings enabled=${settings.enabled} terminalEnabled=${settings.terminalCompletionsEnabled}" }
      return false
    }
    val changes = target.watchChanges()
    val captured = target.captureInput()
    debug { "request capture target=${target.debugIdentity()} input=${captured.debugSummary()}" }
    if (captured == null) { changes.close(); return false }
    val request = PendingTerminalRequest(target, captured, if (forwardTabIfBusy) 1 else 0)
    if (!pending.compareAndSet(null, request)) {
      changes.close()
      val existing = pending.get()
      if (existing == null) {
        debug { "request repeat rejected pending request already cleared" }
        return false
      }
      // Model revisions belong to each target's listener; compare using the original target.
      if (existing.cancelled || existing.target.identity !== target.identity) {
        debug { "request repeat rejected cancelled=${existing.cancelled} existingTarget=${existing.target.debugIdentity()} target=${target.debugIdentity()}" }
        return false
      }
      val existingCurrent = existing.target.captureInput()
      if (existingCurrent != existing.input) {
        debug { "request repeat rejected changed input expected=${existing.input.debugSummary()} current=${existingCurrent.debugSummary()}" }
        return false
      }
      // Repeats during the process probe remain native input if that probe finds a running program.
      if (existing.probing && forwardTabIfBusy) existing.tabs++
      debug { "request repeat accepted target=${existing.target.debugIdentity()} probing=${existing.probing} tabs=${existing.tabs}" }
      return true
    }
    debug { "request accepted target=${target.debugIdentity()} input=${captured.debugSummary()}" }
    // No process probe, cwd lookup, or filesystem collection may inherit EDT/read access.
    val job = coroutineScope.launch(Dispatchers.IO) {
      try {
        debug { "probe started target=${target.debugIdentity()}" }
        val busy = target.isCommandRunning()
        debug { "probe finished target=${target.debugIdentity()} busy=$busy" }
        val eligible = withContext(Dispatchers.EDT) {
          if (request.cancelled || project.isDisposed) {
            debug { "postprobe rejected target=${target.debugIdentity()} cancelled=${request.cancelled} disposed=${project.isDisposed}" }
            false
          }
          else if (busy) {
            request.probing = false
            request.cancelled = true
            debug { "postprobe rejected busy target=${target.debugIdentity()} forwardingTabs=${request.tabs}" }
            request.forwardTabs()
            false
          } else {
            request.probing = false
            request.tabs = 0
            val current = target.captureInput()
            debug { "postprobe capture target=${target.debugIdentity()} matches=${current == captured} expected=${captured.debugSummary()} current=${current.debugSummary()}" }
            current == captured
          }
        }
        if (!eligible) return@launch
        debug { "context collection started target=${target.debugIdentity()}" }
        val context = target.collectContext(captured)
        debug { "context collection finished target=${target.debugIdentity()}" }
        val current = withContext(Dispatchers.EDT) {
          if (project.isDisposed) {
            debug { "postcontext rejected disposed target=${target.debugIdentity()}" }
            false
          } else {
            val latest = target.captureInput()
            debug { "postcontext capture target=${target.debugIdentity()} matches=${latest == captured} expected=${captured.debugSummary()} current=${latest.debugSummary()}" }
            latest == captured
          }
        }
        if (current) {
          debug { "preparation handing off to generation target=${target.debugIdentity()}" }
          generate(captured, context, target, settings, backend)
        }
      } catch (cancelled: CancellationException) {
        debug { "preparation cancelled target=${target.debugIdentity()}" }
        throw cancelled
      } catch (error: Exception) {
        debug { "preparation failed target=${target.debugIdentity()} errorClass=${error.javaClass.name}" }
        withContext(Dispatchers.EDT) {
          if (!project.isDisposed && request.probing && !request.cancelled) {
            request.probing = false
            request.cancelled = true
            request.forwardTabs()
          }
        }
        DiagnosticsLog.getInstance().error("Terminal request preparation failed", "${error.javaClass.name}: ${error.message.orEmpty()}")
      }
    }
    // Also detach if project disposal cancels the coroutine before its body starts.
    job.invokeOnCompletion {
      try { changes.close() } finally { pending.compareAndSet(request, null) }
    }
    return true
  }

  fun isRunning(): Boolean = pending.get() != null

  // The platform removes child terminal callbacks and cancels the injected scope
  // when this plugin unloads, even while the project and terminal remain open.
  override fun dispose() = Unit

  /** Mutable fields are confined to EDT; the active reference is cleared on coroutine completion. */
  private class PendingTerminalRequest(
    val target: TerminalCompletionTarget,
    val input: TerminalCommandInput,
    var tabs: Int,
    var probing: Boolean = true,
    var cancelled: Boolean = false,
  ) {
    fun forwardTabs() {
      val count = tabs
      tabs = 0
      if (count > 0) target.forwardTabs(input, count)
    }
  }

  private suspend fun generate(
    captured: TerminalCommandInput,
    context: TerminalPromptContext,
    target: TerminalCompletionTarget,
    settings: AutocompleteSettings.SettingsState,
    backend: (ProviderKind) -> CompletionBackend,
  ) {
    val runtime = CompletionRuntimeState.getInstance(project)
    val diagnostics = DiagnosticsLog.getInstance()
    val requestId = runtime.nextRequestId()
    val startedAt = System.nanoTime()
    val provider = settings.selectedProvider()
    observe(runtime, requestId, CompletionPipelineStage.TRIGGERED, startedAt, provider)
    diagnostics.info(
      "Terminal command #$requestId started",
      "Provider: ${provider.displayName}; shell: ${context.shell}; " +
        "project markers: ${context.projectMarkers.size}",
    )
    try {
      observe(runtime, requestId, CompletionPipelineStage.CONTEXT_READY, startedAt, provider)
      val prompt = TerminalCommandPromptBuilder.build(context)
      val requestSettings = settings.copy(maxOutputTokens = minOf(settings.maxOutputTokens, TERMINAL_OUTPUT_TOKENS))
      observe(runtime, requestId, CompletionPipelineStage.BACKEND_STARTED, startedAt, provider)
      when (val result = backend(provider).complete(prompt, requestSettings)) {
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
          val commandRunning = withContext(Dispatchers.IO) { target.isCommandRunning() }
          val inserted = !commandRunning && withContext(Dispatchers.EDT) {
            val latestSettings = AutocompleteSettings.getInstance().snapshot()
            if (project.isDisposed || !latestSettings.enabled || !latestSettings.terminalCompletionsEnabled ||
              latestSettings.settingsRevision != settings.settingsRevision
            ) {
              false
            } else {
              target.sendIfCurrent(captured, "\u0015$command")
            }
          }
          if (!inserted) {
            diagnostics.info(
              "Terminal command #$requestId discarded",
              "Settings, terminal focus, shell state, or input changed before the provider returned.",
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

  /**
   * IntelliJ 262 replaces TerminalView.getCurrentDirectory() with workingDirectoryFlow.
   * The old getter is used only on the older API shape where it is not deprecated.
   * A missing value or failed read from the replacement must not invoke the old getter.
   */
  internal object TerminalWorkingDirectory {
    fun resolve(terminal: Any): String? = runCatching {
      val replacement = findNoArgs(terminal, "getWorkingDirectoryFlow")
      val directory = if (replacement != null) {
        replacement.invoke(terminal)?.let { invokeNoArgs(it, "getValue") }
      } else {
        invokeNoArgs(terminal, "getCurrentDirectory")
      }
      directory?.toString()?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun invokeNoArgs(target: Any, name: String): Any? = runCatching {
      findNoArgs(target, name)?.invoke(target)
    }.getOrNull()

    private fun findNoArgs(target: Any, name: String) = target.javaClass.methods.firstOrNull { method ->
      method.name == name && method.parameterCount == 0
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

  private inline fun debug(message: () -> String) {
    if (LOG.isDebugEnabled) LOG.debug(message())
  }

  private fun TerminalCompletionTarget.debugIdentity(): String =
    "${javaClass.simpleName}@${System.identityHashCode(identity).toString(16)}"

  private fun TerminalCommandInput?.debugSummary(): String =
    if (this == null) "absent"
    else "present(textLength=${text.length}, descriptionLength=${description.length}, revision=$revision, modelRevision=$modelRevision)"

  companion object {
    private val LOG = Logger.getInstance(TerminalCompletionService::class.java)

    fun getInstance(project: Project): TerminalCompletionService = project.getService(TerminalCompletionService::class.java)
  }
}
