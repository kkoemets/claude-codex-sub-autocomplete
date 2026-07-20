package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.context.ContextEngine
import com.kkoemets.subscriptionautocomplete.context.ContextFragmentSource
import com.kkoemets.subscriptionautocomplete.context.ContextSharingPolicy
import com.kkoemets.subscriptionautocomplete.context.EditorContextTracker
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettingsListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionInlineCompletionContextTest : SubscriptionInlineCompletionTestCase() {
  fun testLoadingPersistentSettingsDoesNotPublishAReentrantRuntimeChange() {
    EditorContextTracker.getInstance(project)
    var observedChanges = 0
    ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
      AutocompleteSettings.SETTINGS_CHANGED_TOPIC,
      AutocompleteSettingsListener { observedChanges += 1 },
    )
    val settings = AutocompleteSettings.getInstance()
    val loadedState = settings.snapshot().apply { debounceMs += 1 }

    settings.loadState(loadedState)

    assertEquals(0, observedChanges)
    settings.update { it.debounceMs += 1 }
    assertEquals(1, observedChanges)
  }

  fun testRecentEditContextUsesOnlyCachedProjectDocumentsWhenEnabledAndConsented() {
    AutocompleteSettings.getInstance().update { state ->
      state.recentEditContextEnabled = true
      state.allowCrossFileForSubscription = true
    }
    EditorContextTracker.getInstance(project).reconcile()
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
    val settings = AutocompleteSettings.getInstance().snapshot()
    val editor = myFixture.editor
    val caretOffset = ApplicationManager.getApplication().runReadAction<Int> { myFixture.caretOffset }

    val context = runBlocking {
      ContextEngine.getInstance(project).gather(
        editor,
        caretOffset,
        800,
        ContextSharingPolicy.from(CompletionDestination.SUBSCRIPTION_PROCESS, settings),
        CompletionMode.MANUAL,
      )
    }

    assertTrue(context!!.fragments.any { fragment ->
      fragment.source == ContextFragmentSource.RECENT_EDIT &&
        fragment.content.contains("recent helper implementation")
    })
  }

  fun testDisablingEditorContextClearsTrackedMetadata() {
    val settings = AutocompleteSettings.getInstance()
    settings.update { it.recentEditContextEnabled = true }
    val tracker = EditorContextTracker.getInstance(project)
    tracker.reconcile()

    settings.update {
      it.recentEditContextEnabled = false
      it.openTabContextEnabled = false
    }

    val snapshot = tracker.snapshot()
    assertTrue(snapshot.recentEdits.isEmpty())
    assertTrue(snapshot.openTabs.isEmpty())
  }
}
