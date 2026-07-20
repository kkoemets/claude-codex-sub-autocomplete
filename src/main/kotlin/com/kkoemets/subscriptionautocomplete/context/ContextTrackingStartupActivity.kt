package com.kkoemets.subscriptionautocomplete.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ContextTrackingStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    EditorContextTracker.getInstance(project).reconcile()
  }
}
