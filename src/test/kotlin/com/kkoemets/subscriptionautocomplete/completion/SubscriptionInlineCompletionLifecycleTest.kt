package com.kkoemets.subscriptionautocomplete.completion

import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.fileTypes.PlainTextFileType
import kotlin.test.assertEquals

class SubscriptionInlineCompletionLifecycleTest : SubscriptionInlineCompletionTestCase() {
  fun testManualCompletionRendersAndInsertsThroughTheProductionPipeline() {
    val backend = FixedBackend("42")
    val stages = mutableListOf<CompletionPipelineStage>()
    installBackend(backend, CompletionPipelineObserver { stages += it.stage })

    myFixture.testInlineCompletion {
      init(PlainTextFileType.INSTANCE, "val answer = <caret>")
      callInlineCompletion()
      delay()
      assertInlineRender("42")
      insert()
      assertFileContent("val answer = 42<caret>")
      assertInlineHidden()
    }

    assertEquals(1, backend.calls)
    assertEquals(
      listOf(
        CompletionPipelineStage.TRIGGERED,
        CompletionPipelineStage.CONTEXT_READY,
        CompletionPipelineStage.BACKEND_STARTED,
        CompletionPipelineStage.BACKEND_FINISHED,
        CompletionPipelineStage.SANITIZED,
        CompletionPipelineStage.VALIDATED,
        CompletionPipelineStage.RENDER_READY,
      ),
      stages,
    )
  }

  fun testAutomaticCommentCompletionRepairsTheWordBoundary() {
    val backend = FixedBackend("install the dependencies")
    installBackend(backend)

    myFixture.testInlineCompletion {
      init(PlainTextFileType.INSTANCE, "# this wil<caret>")
      typeChar('l')
      delay()
      assertInlineRender(" install the dependencies")
      insert()
      assertFileContent("# this will install the dependencies<caret>")
    }

    assertEquals(1, backend.calls)
  }
}
