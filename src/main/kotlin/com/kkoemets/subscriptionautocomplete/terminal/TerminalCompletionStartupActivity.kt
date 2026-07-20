package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** Installs Tab interception for IntelliJ's classic, Reworked 2025, and newer terminal surfaces. */
@Suppress("UnstableApiUsage")
class TerminalCompletionStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    TerminalWidgetTabInstaller(project).install()
  }
}

@Suppress("UnstableApiUsage")
internal class TerminalWidgetTabInstaller(
  private val project: Project,
) {
  private val manager = TerminalToolWindowManager.getInstance(project)

  fun install() {
    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val dispatcher = KeyEventDispatcher(::intercept)
    focusManager.addKeyEventDispatcher(dispatcher)
    Disposer.register(project, Disposable { focusManager.removeKeyEventDispatcher(dispatcher) })

    manager.addNewTerminalSetupHandler(
      { terminal -> terminalRegistered(terminal) },
      project,
    )
    DiagnosticsLog.getInstance().info(
      "Terminal Tab integration attached",
      "Focused-widget interception is active; compatible with classic and Reworked Terminal sessions. " +
        "Registered sessions: ${runCatching { manager.terminalWidgets.size }.getOrDefault(0)}.",
    )
  }

  private fun terminalRegistered(@Suppress("UNUSED_PARAMETER") terminal: TerminalWidget) {
    DiagnosticsLog.getInstance().infoCoalesced(
      key = "terminal-widget-registered",
      summary = "Terminal session registered",
      details = "Tab command generation is available when this terminal has keyboard focus.",
      intervalMillis = 2_000,
    )
  }

  private fun intercept(event: KeyEvent): Boolean {
    if (!TerminalTabKey.isPlainTabPress(event)) return false
    val settings = AutocompleteSettings.getInstance().snapshot()
    if (!settings.enabled || !settings.terminalCompletionsEnabled) return false
    val terminal = focusedTerminal() ?: return false
    TerminalCompletionService.getInstance(project).request(terminal)
    return true
  }

  private fun focusedTerminal(): TerminalWidget? = runCatching {
    manager.terminalWidgets.firstOrNull { it.hasFocus() }
  }.getOrNull()
}

internal object TerminalTabKey {
  private const val COMMAND_MODIFIERS =
    InputEvent.SHIFT_DOWN_MASK or
      InputEvent.CTRL_DOWN_MASK or
      InputEvent.ALT_DOWN_MASK or
      InputEvent.META_DOWN_MASK or
      InputEvent.ALT_GRAPH_DOWN_MASK

  fun isPlainTabPress(event: KeyEvent?): Boolean =
    event != null &&
      event.id == KeyEvent.KEY_PRESSED &&
      event.keyCode == KeyEvent.VK_TAB &&
      event.modifiersEx and COMMAND_MODIFIERS == 0
}
