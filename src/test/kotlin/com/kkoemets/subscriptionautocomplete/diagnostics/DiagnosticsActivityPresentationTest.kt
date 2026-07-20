package com.kkoemets.subscriptionautocomplete.diagnostics

import com.kkoemets.subscriptionautocomplete.completion.CompletionActivityPhase
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivitySnapshot
import com.kkoemets.subscriptionautocomplete.completion.CompletionCandidateSource
import com.kkoemets.subscriptionautocomplete.completion.CompletionStageTimings
import com.kkoemets.subscriptionautocomplete.completion.CompletionTerminalReason
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsActivityPresentationTest {
  @Test
  fun `idle invalidation is not presented as a completion`() {
    assertEquals(
      "Last completion: none.",
      DiagnosticsActivityPresentation.summary(
        CompletionActivitySnapshot(12, CompletionActivityPhase.IDLE, 0),
        ProviderKind.CODEX,
      ),
    )
  }

  @Test
  fun `summary exposes source outcome and stage timings`() {
    val summary = DiagnosticsActivityPresentation.summary(
      CompletionActivitySnapshot(
        requestId = 42,
        phase = CompletionActivityPhase.NO_RESULT,
        elapsedMillis = 130,
        source = CompletionCandidateSource.PROVIDER,
        provider = ProviderKind.CLAUDE,
        terminalReason = CompletionTerminalReason.SYNTAX_REJECTED,
        timings = CompletionStageTimings(
          contextMillis = 10,
          providerMillis = 100,
          sanitizeMillis = 5,
          validationMillis = 15,
          totalMillis = 130,
        ),
      ),
      ProviderKind.CODEX,
    )

    assertEquals(
      "Last completion: #42 · Claude provider · syntax rejected · context 10 ms · " +
        "provider 100 ms · sanitize 5 ms · validation 15 ms · total 130 ms",
      summary,
    )
  }

  @Test
  fun `summary model cannot contain source or path text`() {
    val summary = DiagnosticsActivityPresentation.summary(
      CompletionActivitySnapshot(
        requestId = 7,
        phase = CompletionActivityPhase.READY,
        elapsedMillis = 20,
        source = CompletionCandidateSource.CACHE,
        terminalReason = CompletionTerminalReason.READY,
      ),
      ProviderKind.CODEX,
    )

    assertTrue(summary.contains("local cache"))
    assertTrue(!summary.contains("/Users/"))
    assertTrue(!summary.contains(".ts"))
  }
}
