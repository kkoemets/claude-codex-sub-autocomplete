package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import com.jediterm.terminal.model.TerminalModelListener
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.plugins.terminal.ShellTerminalWidget

internal data class TerminalCommandInput(val text: String, val description: String, val revision: Long, val modelRevision: Long = 0)

/** UI snapshots and process/filesystem probes deliberately have separate thread contracts. */
internal interface TerminalCompletionTarget {
  val identity: Any get() = this
  fun captureInput(): TerminalCommandInput?
  fun watchChanges(): AutoCloseable = AutoCloseable {}
  fun isCommandRunning(): Boolean
  fun collectContext(input: TerminalCommandInput): TerminalPromptContext
  fun sendText(text: String)
  fun sendIfCurrent(input: TerminalCommandInput, text: String): Boolean {
    if (captureInput() != input) return false
    sendText(text)
    return true
  }
  fun forwardTabs(input: TerminalCommandInput, count: Int): Boolean = sendIfCurrent(input, "\t".repeat(count))
}

@Suppress("UnstableApiUsage")
internal class ClassicTerminalCompletionTarget(
  private val widget: TerminalWidget,
  private val revision: AtomicLong,
  private val projectName: String,
  private val projectBasePath: String?,
) : TerminalCompletionTarget {
  override val identity: Any get() = widget
  private val shell = ShellTerminalWidget.asShellJediTermWidget(widget)
  private val starter = shell?.terminalStarter
  private val modelRevision = AtomicLong()

  override fun captureInput(): TerminalCommandInput? {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val before = revision.get()
    val modelBefore = modelRevision.get()
    val shell = shell ?: return null
    if (starter == null || shell.terminalStarter !== starter) return null
    val text = readInput(widget) ?: return null
    val description = TerminalCommandTrigger.extract(text) ?: return null
    return TerminalCommandInput(text, description, before, modelBefore)
      .takeIf { before == revision.get() && modelBefore == modelRevision.get() }
  }

  override fun watchChanges(): AutoCloseable {
    val buffer = shell?.terminalPanel?.terminalTextBuffer ?: return AutoCloseable {}
    val listener = TerminalModelListener { modelRevision.incrementAndGet() }
    buffer.addModelListener(listener)
    return AutoCloseable { buffer.removeModelListener(listener) }
  }

  override fun isCommandRunning(): Boolean = widget.isCommandRunning()

  override fun collectContext(input: TerminalCommandInput): TerminalPromptContext =
    TerminalProjectContextCollector.collect(
      input.description, widget.shellCommand.orEmpty(), widget.getCurrentDirectory(), projectName, projectBasePath,
    )

  override fun sendText(text: String) {
    ApplicationManager.getApplication().assertIsDispatchThread()
    check(starter != null && shell?.terminalStarter === starter) { "Terminal session changed before insertion" }
    // Use the terminal's input queue, never a future connector callback or blocking EDT write.
    starter.sendString(text, false)
  }

  override fun sendIfCurrent(input: TerminalCommandInput, text: String): Boolean {
    val buffer = shell?.terminalPanel?.terminalTextBuffer ?: return false
    if (!buffer.tryLock()) return false
    try {
      return super.sendIfCurrent(input, text)
    } finally {
      buffer.unlock()
    }
  }

  override fun forwardTabs(input: TerminalCommandInput, count: Int): Boolean {
    ApplicationManager.getApplication().assertIsDispatchThread()
    // Program output may redraw while the probe runs. Native Tab only depends on
    // the original session, keyboard focus, and user-input ordering, not output text.
    if (shell == null || revision.get() != input.revision || starter == null || shell.terminalStarter !== starter ||
      !TerminalInputFocus.isFocused(shell.terminalPanel)) return false
    starter.sendString("\t".repeat(count), false)
    return true
  }

  companion object {
    fun readInput(widget: TerminalWidget): String? {
      ApplicationManager.getApplication().assertIsDispatchThread()
      val shell = ShellTerminalWidget.asShellJediTermWidget(widget) ?: return null
      if (!TerminalInputFocus.isFocused(shell.terminalPanel)) return null
      val buffer = shell.terminalPanel.terminalTextBuffer
      // The prompt getter takes this reentrant lock internally; do not wait for the emulator on EDT.
      if (!buffer.tryLock()) return null
      try {
        if (buffer.isUsingAlternateBuffer) return null
        // Classic's keyboard-history heuristic misses paste/IME input. Read only
        // the current cursor row as a fallback; the background process probe
        // still decides whether the shell owns input before generation/insertion.
        val row = (shell.terminal.cursorY - 1).coerceIn(0, buffer.height - 1)
        return ClassicTerminalPromptInput.read(shell.typedShellCommand, buffer.getLine(row).text)
      } finally {
        buffer.unlock()
      }
    }
  }
}

@Suppress("UnstableApiUsage")
internal class ReworkedTerminalCompletionTarget(
  private val terminal: TerminalView,
  private val revision: AtomicLong,
  private val projectName: String,
  private val projectBasePath: String?,
) : TerminalCompletionTarget {
  override val identity: Any get() = terminal
  override fun captureInput(): TerminalCommandInput? {
    ApplicationManager.getApplication().assertIsDispatchThread()
    val text = readInput(terminal) ?: return null
    return TerminalCommandInput(text, requireNotNull(TerminalCommandTrigger.extract(text)), revision.get())
  }

  // Reworked shell integration is checked synchronously by captureInput; no OS process probe is needed.
  override fun isCommandRunning(): Boolean = false

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun collectContext(input: TerminalCommandInput): TerminalPromptContext {
    val deferred = terminal.startupOptionsDeferred
    val options = if (deferred.isCompleted && !deferred.isCancelled) deferred.getCompleted() else null
    return TerminalProjectContextCollector.collect(
      input.description, options?.shellCommand.orEmpty(), TerminalCompletionService.TerminalWorkingDirectory.resolve(terminal),
      projectName, projectBasePath,
    )
  }

  override fun sendText(text: String) = terminal.sendText(text)

  companion object {
    fun readInput(terminal: TerminalView): String? {
      ApplicationManager.getApplication().assertIsDispatchThread()
      if (!TerminalInputFocus.isFocused(terminal.preferredFocusableComponent)) return null
      return TerminalPromptInput.read(terminal)?.takeIf { TerminalCommandTrigger.extract(it) != null }
    }
  }
}
