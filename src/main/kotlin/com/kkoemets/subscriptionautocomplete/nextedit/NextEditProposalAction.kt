package com.kkoemets.subscriptionautocomplete.nextedit

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

class NextEditProposalAction : AnAction(), DumbAware {
  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val editor = event.getData(CommonDataKeys.EDITOR)
      ?: FileEditorManager.getInstance(project).selectedTextEditor
      ?: return
    NextEditProposalService.getInstance(project).request(editor)
  }

  override fun update(event: AnActionEvent) {
    val project = event.project
    val settings = AutocompleteSettings.getInstance().state
    val editorAvailable = event.getData(CommonDataKeys.EDITOR) != null ||
      project?.let { FileEditorManager.getInstance(it).selectedTextEditor } != null
    event.presentation.isEnabled = project != null && editorAvailable && settings.enabled &&
      settings.allowCrossFileForSubscription && !NextEditProposalService.getInstance(project).isRunning()
    event.presentation.description = if (!settings.allowCrossFileForSubscription) {
      "Enable subscription cross-file context in settings to request a related-edit proposal"
    } else {
      "Request a read-only related-edit proposal from the selected subscription provider"
    }
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  companion object {
    fun request(project: Project) {
      val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
      NextEditProposalService.getInstance(project).request(editor)
    }
  }
}
