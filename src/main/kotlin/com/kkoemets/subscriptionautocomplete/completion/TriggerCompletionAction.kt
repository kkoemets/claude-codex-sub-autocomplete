package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware

class TriggerCompletionAction : AnAction(), DumbAware {
  override fun actionPerformed(event: AnActionEvent) {
    if (!AutocompleteSettings.getInstance().state.enabled) return
    val project = event.project ?: return
    val editor = event.getData(CommonDataKeys.EDITOR)
      ?: FileEditorManager.getInstance(project).selectedTextEditor
      ?: return
    val actionManager = ActionManager.getInstance()
    val action = actionManager.getAction(IdeActions.ACTION_CALL_INLINE_COMPLETION) ?: return
    actionManager.tryToExecute(action, event.inputEvent, editor.contentComponent, event.place, true)
  }

  override fun update(event: AnActionEvent) {
    val editorAvailable = event.getData(CommonDataKeys.EDITOR) != null ||
      event.project?.let { FileEditorManager.getInstance(it).selectedTextEditor } != null
    event.presentation.isEnabled = editorAvailable && AutocompleteSettings.getInstance().state.enabled
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  companion object {
    fun trigger(project: Project) {
      if (!AutocompleteSettings.getInstance().state.enabled) return
      val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
      val actionManager = ActionManager.getInstance()
      val action = actionManager.getAction(IdeActions.ACTION_CALL_INLINE_COMPLETION) ?: return
      actionManager.tryToExecute(action, null, editor.contentComponent, ACTION_PLACE, true)
    }

    private const val ACTION_PLACE = "SubscriptionAutocompleteStatusBar"
  }
}
