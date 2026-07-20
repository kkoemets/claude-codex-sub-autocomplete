package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.codeInsight.inline.completion.testInlineCompletion
import com.intellij.openapi.fileTypes.PlainTextFileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals

class SubscriptionInlineCompletionCancellationTest : SubscriptionInlineCompletionTestCase() {
  fun testSupersededRequestCannotRenderAfterTheLatestRequest() {
    val backend = BarrierBackend()
    installBackend(backend)

    myFixture.testInlineCompletion {
      init(PlainTextFileType.INSTANCE, "val res<caret>")
      typeChar('u')
      val first = withTimeout(5_000) { backend.requests.receive() }

      typeChar('l')
      val second = withTimeout(5_000) { backend.requests.receive() }
      withTimeout(5_000) { first.cancelled.await() }

      first.response.complete(BackendResult.Success("WRONG", "fake"))
      second.response.complete(BackendResult.Success("t = compute()", "fake"))
      delay()

      assertInlineRender("t = compute()")
      insert()
      assertFileContent("val result = compute()<caret>")
    }

    assertEquals(2, backend.started)
  }

  private class BarrierBackend : CompletionBackend {
    override val provider: ProviderKind = ProviderKind.CODEX
    val requests = Channel<Pending>(Channel.UNLIMITED)
    var started = 0
      private set

    override suspend fun complete(
      prompt: CompletionPrompt,
      settings: AutocompleteSettings.SettingsState,
    ): BackendResult {
      started += 1
      val pending = Pending()
      requests.send(pending)
      return try {
        pending.response.await()
      } catch (cancelled: CancellationException) {
        pending.cancelled.complete(Unit)
        throw cancelled
      }
    }
  }

  private class Pending {
    val response = CompletableDeferred<BackendResult>()
    val cancelled = CompletableDeferred<Unit>()
  }
}
