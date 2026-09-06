package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.EDT
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.terminal.ui.TerminalWidget
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import java.awt.AWTEvent
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installs Tab interception for IntelliJ's classic, Reworked 2025, and newer terminal surfaces. */
@Suppress("UnstableApiUsage")
class TerminalCompletionStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    withContext(Dispatchers.EDT) {
      if (!project.isDisposed) TerminalWidgetTabInstaller(project).install()
    }
  }
}

@Suppress("UnstableApiUsage")
internal class TerminalWidgetTabInstaller(
  private val project: Project,
) {
  private val manager = TerminalToolWindowManager.getInstance(project)
  private val service = TerminalCompletionService.getInstance(project)
  private val tabSequence = TerminalTabSequence()
  private val logger = com.intellij.openapi.diagnostic.Logger.getInstance(TerminalWidgetTabInstaller::class.java)

  fun install() {
    // This bucket runs before terminal dispatchers even for restored sessions.
    // AWT key dispatchers run too late: terminal input may already have been sent.
    val dispatcher = object : IdeEventQueue.NonLockedEventDispatcher {
      override fun dispatch(e: AWTEvent): Boolean {
        if (logger.isDebugEnabled && e is KeyEvent && (e.keyCode == KeyEvent.VK_TAB || e.keyChar == '\t')) {
          logger.debug("Terminal Tab event: id=${e.id}; modifiers=${e.modifiersEx}; consumed=${e.isConsumed}; " +
            "focus=${KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.javaClass?.name}")
        }
        if (TerminalTabKey.changesInput(e)) {
          service.inputChanged()
        }
        val handled = intercept(e as? KeyEvent)
        if (logger.isDebugEnabled && e is KeyEvent && e.keyCode == KeyEvent.VK_TAB) {
          logger.debug("Terminal Tab result: id=${e.id}; handled=$handled")
        }
        return handled
      }
    }
    IdeEventQueue.getInstance().addDispatcher(dispatcher, project)

    manager.addNewTerminalSetupHandler(
      { terminal -> terminalRegistered(terminal) },
      project,
    )
    DiagnosticsLog.getInstance().info(
      "Terminal Tab integration attached",
      "Focused-terminal interception is active for classic widgets and Reworked views. " +
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

  private fun intercept(event: KeyEvent?): Boolean = tabSequence.dispatch(event) {
    if (project.isDisposed) return@dispatch false
    val settings = AutocompleteSettings.getInstance().snapshot()
    if (!settings.enabled || !settings.terminalCompletionsEnabled) return@dispatch false
    // Native events may still name the frame here; AWT retargets them later.
    val component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@dispatch false
    val context = DataManager.getInstance().getDataContext(component)
    if (logger.isDebugEnabled) {
      logger.debug("Terminal Tab routing: projectMatches=${CommonDataKeys.PROJECT.getData(context) == project}; " +
        "reworked=${focusedTerminalView(context) != null}; classic=${focusedTerminal() != null}")
    }
    if (CommonDataKeys.PROJECT.getData(context) != project) return@dispatch false
    focusedTerminalView(context)?.let { view ->
      return@dispatch service.request(view)
    }
    focusedTerminal()?.let { terminal ->
      return@dispatch service.request(terminal, forwardTabIfBusy = true)
    }
    false
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
      !event.isConsumed &&
      event.id == KeyEvent.KEY_PRESSED &&
      event.keyCode == KeyEvent.VK_TAB &&
      event.modifiersEx and COMMAND_MODIFIERS == 0

  fun isUnmappedKeyEvent(event: KeyEvent): Boolean =
    event.id != KeyEvent.KEY_TYPED && event.keyCode == KeyEvent.VK_UNDEFINED

  fun changesInput(event: AWTEvent): Boolean =
    event is java.awt.event.InputMethodEvent || event is KeyEvent &&
      (event.id == KeyEvent.KEY_PRESSED && !isUnmappedKeyEvent(event) && !isPlainTabPress(event) ||
        event.id == KeyEvent.KEY_TYPED && event.keyChar != '\t')
}

/** Consume the typed/released companions only when we took ownership of the press. */
internal class TerminalTabSequence {
  private var handledPress = false

  fun dispatch(event: KeyEvent?, request: () -> Boolean): Boolean {
    if (event == null) return false
    // Synthetic wake-up/unmapped presses have no native key action. Actual
    // Unicode/IME text is handled through KEY_TYPED/InputMethodEvent instead.
    if (TerminalTabKey.isUnmappedKeyEvent(event)) return false
    if (event.id == KeyEvent.KEY_TYPED && event.keyChar == '\t' && handledPress) return true
    if (event.id == KeyEvent.KEY_RELEASED && event.keyCode == KeyEvent.VK_TAB) {
      val handled = handledPress
      handledPress = false
      return handled
    }
    if (!TerminalTabKey.isPlainTabPress(event)) {
      if (event.id == KeyEvent.KEY_PRESSED) handledPress = false
      return false
    }
    val handled = request()
    handledPress = handled
    return handled
  }
}
