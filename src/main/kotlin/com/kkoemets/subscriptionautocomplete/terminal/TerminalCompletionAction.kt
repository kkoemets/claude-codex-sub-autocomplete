package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalAllowedActionsProvider
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.awt.Component
import java.awt.KeyboardFocusManager
import javax.swing.SwingUtilities

@Suppress("UnstableApiUsage")
class TerminalCompletionAction : DumbAwareAction() {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val service = TerminalCompletionService.getInstance(project)
    focusedTerminal(project)?.let {
      service.request(it)
      return
    }
    terminalView(event)?.let { service.request(it) }
  }

  override fun update(event: AnActionEvent) {
    val project = event.project
    val service = project?.let(TerminalCompletionService::getInstance)
    event.presentation.isEnabled = service != null &&
      (focusedTerminal(project)?.let(service::canRequest) == true ||
        terminalView(event)?.let(service::canRequest) == true)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  private fun terminalView(event: AnActionEvent): TerminalView? =
    focusedTerminalView(event.dataContext)

  private fun focusedTerminal(project: com.intellij.openapi.project.Project): TerminalWidget? = runCatching {
    TerminalToolWindowManager.getInstance(project).terminalWidgets.firstOrNull { it.hasFocus() }
  }.getOrNull()

  companion object {
    const val ID = "SubscriptionAutocomplete.GenerateTerminalCommand"
  }
}

private val LOWERCASE_TERMINAL_VIEW_KEY = DataKey.create<TerminalView>("terminalView")

/** The Reworked terminal exposes a view in its data context, not a legacy widget. */
internal fun focusedTerminalView(context: DataContext): TerminalView? =
  TerminalView.DATA_KEY.getData(context) ?: LOWERCASE_TERMINAL_VIEW_KEY.getData(context)

internal object TerminalInputFocus {
  fun isFocused(input: Component): Boolean = contains(input, KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner)

  fun contains(input: Component, focused: Component?): Boolean =
    focused != null && (focused === input || SwingUtilities.isDescendingFrom(focused, input))
}

@Suppress("UnstableApiUsage")
class TerminalCompletionAllowedActionsProvider : TerminalAllowedActionsProvider {
  override fun getActionIds(): List<String> = listOf(TerminalCompletionAction.ID)
}
