package com.kkoemets.subscriptionautocomplete.nextedit

import com.kkoemets.subscriptionautocomplete.context.EditorContextTracker
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextEditContextCollectorTest : BasePlatformTestCase() {
  private lateinit var originalSettings: AutocompleteSettings.SettingsState

  override fun runInDispatchThread(): Boolean = false

  override fun setUp() {
    super.setUp()
    val settings = AutocompleteSettings.getInstance()
    originalSettings = settings.snapshot()
    settings.update { state ->
      state.enabled = true
      state.recentEditContextEnabled = true
      state.allowCrossFileForSubscription = true
    }
    EditorContextTracker.getInstance(project).reconcile()
  }

  override fun tearDown() {
    try {
      AutocompleteSettings.getInstance().loadState(originalSettings)
    } finally {
      super.tearDown()
    }
  }

  fun testCollectorKeepsActiveReadOnlyAndIncludesBoundedRecentTargetsWithStamps() {
    val helper = myFixture.addFileToProject("src/helper.txt", "helper value")
    val helperDocument = ApplicationManager.getApplication().runReadAction<Document> {
      PsiDocumentManager.getInstance(project).getDocument(helper)!!
    }
    ApplicationManager.getApplication().invokeAndWait {
      WriteCommandAction.runWriteCommandAction(project) {
        helperDocument.insertString(helperDocument.textLength, "\nrecent helper implementation")
        PsiDocumentManager.getInstance(project).commitDocument(helperDocument)
      }
    }
    myFixture.configureByText("target.txt", "target <caret>")

    val context = requireNotNull(
      runBlocking { NextEditContextCollector(project).collect(myFixture.editor, 1_200) },
    )
    assertEquals(myFixture.editor.document.modificationStamp, context.activeModificationStamp)
    assertEquals(myFixture.file.virtualFile, context.activeFile)
    assertTrue(context.targets.none { it.id == "target-0" })
    val related = context.targets.single { it.displayName == "helper.txt" }
    assertTrue(related.excerpt.contains("recent helper implementation"))
    assertEquals(helperDocument.modificationStamp, related.modificationStamp)
    assertEquals(helper.virtualFile.url, related.fileIdentity?.url)
    assertTrue(context.targets.size <= 3)
  }

  fun testProposalGuardRejectsChangedTargetActiveFileAndSettings() {
    val helper = myFixture.addFileToProject("src/guard-helper.txt", "helper value")
    val helperDocument = ApplicationManager.getApplication().runReadAction<Document> {
      PsiDocumentManager.getInstance(project).getDocument(helper)!!
    }
    ApplicationManager.getApplication().invokeAndWait {
      WriteCommandAction.runWriteCommandAction(project) {
        helperDocument.insertString(helperDocument.textLength, "\nrecent guarded implementation")
        PsiDocumentManager.getInstance(project).commitDocument(helperDocument)
      }
    }
    myFixture.configureByText("guard-target.txt", "target <caret>")
    val context = requireNotNull(
      runBlocking { NextEditContextCollector(project).collect(myFixture.editor, 1_200) },
    )
    val settings = AutocompleteSettings.getInstance().snapshot()
    val provider = settings.selectedProvider()

    assertNull(nextEditDiscardReason(project, context, settings.settingsRevision, provider))
    ApplicationManager.getApplication().invokeAndWait {
      myFixture.editor.caretModel.moveToOffset(0)
    }
    assertEquals(
      "active-file-changed",
      nextEditDiscardReason(project, context, settings.settingsRevision, provider),
    )
    ApplicationManager.getApplication().invokeAndWait {
      myFixture.editor.caretModel.moveToOffset(context.activeCaretOffset)
      WriteCommandAction.runWriteCommandAction(project) {
        helper.virtualFile.rename(this, "guard-helper-renamed.txt")
      }
    }
    assertEquals(
      "target-file-changed",
      nextEditDiscardReason(project, context, settings.settingsRevision, provider),
    )
    ApplicationManager.getApplication().invokeAndWait {
      WriteCommandAction.runWriteCommandAction(project) {
        helper.virtualFile.rename(this, "guard-helper.txt")
      }
    }
    assertNull(nextEditDiscardReason(project, context, settings.settingsRevision, provider))
    ApplicationManager.getApplication().invokeAndWait {
      WriteCommandAction.runWriteCommandAction(project) {
        helperDocument.insertString(helperDocument.textLength, " changed")
      }
    }
    assertEquals(
      "target-file-changed",
      nextEditDiscardReason(project, context, settings.settingsRevision, provider),
    )
    ApplicationManager.getApplication().invokeAndWait {
      WriteCommandAction.runWriteCommandAction(project) {
        myFixture.editor.document.insertString(myFixture.editor.document.textLength, " changed")
      }
    }
    assertEquals(
      "active-file-changed",
      nextEditDiscardReason(project, context, settings.settingsRevision, provider),
    )
    AutocompleteSettings.getInstance().update { state -> state.debounceMs += 1 }
    assertEquals(
      "settings-changed",
      nextEditDiscardReason(project, context, settings.settingsRevision, provider),
    )
  }
}
