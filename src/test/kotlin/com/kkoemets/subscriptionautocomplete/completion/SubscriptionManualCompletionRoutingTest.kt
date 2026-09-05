package com.kkoemets.subscriptionautocomplete.completion

import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.ExtensionTestUtil
import kotlin.test.assertEquals

class SubscriptionManualCompletionRoutingTest : SubscriptionInlineCompletionTestCase() {
  fun testExplicitActionReachesSubscriptionWhenAnotherProviderIsEnabledFirst() {
    val backend = FixedBackend("value * 2")
    val provider = SubscriptionCompletionProvider(
      engineResolver = CompletionEngineResolver(),
      backendLookup = { backend },
    )
    val competing = object : InlineCompletionProvider by provider {
      override val id = InlineCompletionProviderID("CompetingCompletion")
      override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        error("The subscription action was routed to another completion provider")
      }
    }
    ExtensionTestUtil.maskExtensions(
      InlineCompletionProvider.EP_NAME,
      listOf(competing, provider),
      testRootDisposable,
    )

    myFixture.testInlineCompletion {
      init(PlainTextFileType.INSTANCE, "return <caret>;")
      ApplicationManager.getApplication().invokeAndWait {
        TriggerCompletionAction.trigger(myFixture.editor)
      }
      delay()
      assertInlineRender("value * 2")
      insert()
      assertFileContent("return value * 2<caret>;")
    }
    assertEquals(1, backend.calls)
  }
}
