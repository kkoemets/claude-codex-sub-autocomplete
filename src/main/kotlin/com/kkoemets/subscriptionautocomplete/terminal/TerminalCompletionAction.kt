package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.terminal.frontend.view.TerminalAllowedActionsProvider
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

@Suppress("UnstableApiUsage")
class TerminalCompletionAction : DumbAwareAction() {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val service = TerminalCompletionService.getInstance(project)
    focusedTerminal(project)?.let {
      service.request(it)
      return
    }
    terminalView(event)?.let(service::request)
  }

  override fun update(event: AnActionEvent) {
    val project = event.project
    event.presentation.isEnabled = project != null &&
      (focusedTerminal(project) != null || terminalView(event) != null)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  private fun terminalView(event: AnActionEvent): TerminalView? =
    event.getData(TerminalView.DATA_KEY) ?: event.getData(LOWERCASE_TERMINAL_VIEW_KEY)

  private fun focusedTerminal(project: com.intellij.openapi.project.Project): TerminalWidget? = runCatching {
    TerminalToolWindowManager.getInstance(project).terminalWidgets.firstOrNull { it.hasFocus() }
  }.getOrNull()

  companion object {
    const val ID = "SubscriptionAutocomplete.GenerateTerminalCommand"
    private val LOWERCASE_TERMINAL_VIEW_KEY = DataKey.create<TerminalView>("terminalView")
  }
}

@Suppress("UnstableApiUsage")
class TerminalCompletionAllowedActionsProvider : TerminalAllowedActionsProvider {
  override fun getActionIds(): List<String> = listOf(TerminalCompletionAction.ID)
}
