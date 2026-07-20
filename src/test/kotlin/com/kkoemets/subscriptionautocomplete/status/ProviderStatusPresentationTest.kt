package com.kkoemets.subscriptionautocomplete.status

import com.kkoemets.subscriptionautocomplete.completion.CompletionActivityPhase
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivitySnapshot
import com.kkoemets.subscriptionautocomplete.completion.CompletionCandidateSource
import com.kkoemets.subscriptionautocomplete.completion.CompletionStageTimings
import com.kkoemets.subscriptionautocomplete.completion.CompletionTerminalReason
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutomaticCompletionEngine
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderStatusPresentationTest {
  @Test
  fun `related edit request is unmistakably active and read only`() {
    val text = ProviderStatusPresentation.relatedEditText(settings(), ProviderKind.CODEX)
    val tooltip = ProviderStatusPresentation.relatedEditTooltip(settings(), ProviderKind.CODEX)

    assertEquals("AI ◐ related edit · Codex: gpt-test", text)
    assertTrue(tooltip.contains("generating"))
    assertTrue(tooltip.contains("No file will be changed"))
  }

  @Test
  fun `requesting state is unmistakably active`() {
    val settings = settings()
    val activity = CompletionActivitySnapshot(
      7,
      CompletionActivityPhase.REQUESTING,
      120,
      provider = ProviderKind.CODEX,
      startedAtMillis = 1_000,
    )

    assertEquals(
      "AI ◐ · Codex generating · 2.1 s",
      ProviderStatusPresentation.text(settings, ProviderKind.CODEX, activity, nowMillis = 3_100),
    )
    assertTrue(
      ProviderStatusPresentation.tooltip(settings, ProviderKind.CODEX, activity)
        .startsWith("Codex gpt-test is generating"),
    )
  }

  @Test
  fun `terminal states explain the outcome`() {
    val settings = settings()

    assertEquals(
      "AI ✓ ready · Codex",
      ProviderStatusPresentation.text(
        settings,
        ProviderKind.CODEX,
        CompletionActivitySnapshot(
          8,
          CompletionActivityPhase.READY,
          500,
          provider = ProviderKind.CODEX,
          terminalReason = CompletionTerminalReason.READY,
        ),
      ),
    )
    assertEquals(
      "AI ! timed out · Codex",
      ProviderStatusPresentation.text(
        settings,
        ProviderKind.CODEX,
        CompletionActivitySnapshot(
          9,
          CompletionActivityPhase.FAILED,
          500,
          terminalReason = CompletionTerminalReason.TIMEOUT,
        ),
      ),
    )
  }

  @Test
  fun `candidate source is visible without file details`() {
    val activity = CompletionActivitySnapshot(
      9,
      CompletionActivityPhase.READY,
      30,
      source = CompletionCandidateSource.CACHE,
      terminalReason = CompletionTerminalReason.READY,
      timings = CompletionStageTimings(totalMillis = 30),
    )

    assertEquals("AI ✓ ready · cache", ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity))
    val tooltip = ProviderStatusPresentation.tooltip(settings(), ProviderKind.CODEX, activity)
    assertTrue(tooltip.contains("local suggestion cache"))
    assertTrue(!tooltip.contains("/Users/"))
  }

  @Test
  fun `routine supersession quietly returns to normal idle text`() {
    val activity = CompletionActivitySnapshot(
      10,
      CompletionActivityPhase.IDLE,
      0,
      terminalReason = CompletionTerminalReason.SUPERSEDED,
    )

    assertEquals("AI ○ idle · Codex: gpt-test", ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity))
    assertTrue(!ProviderStatusPresentation.tooltip(settings(), ProviderKind.CODEX, activity).contains("superseded"))
  }

  @Test
  fun `elapsed refresh timer stops for terminal state and disposal`() {
    val timer = ProviderStatusRefreshTimer(delayMillis = 60_000) { }
    try {
      timer.update(CompletionActivitySnapshot(1, CompletionActivityPhase.REQUESTING, 0))
      assertTrue(timer.isRunning())

      timer.update(CompletionActivitySnapshot(1, CompletionActivityPhase.READY, 20))
      assertTrue(!timer.isRunning())

      timer.update(CompletionActivitySnapshot(2, CompletionActivityPhase.PREPARING, 0))
      assertTrue(timer.isRunning())
    } finally {
      timer.dispose()
    }
    timer.update(CompletionActivitySnapshot(3, CompletionActivityPhase.REQUESTING, 0))
    assertTrue(!timer.isRunning())
  }

  @Test
  fun `disabled state overrides stale activity`() {
    val settings = settings().apply { enabled = false }

    assertEquals(
      "AI ○ · off",
      ProviderStatusPresentation.text(
        settings,
        ProviderKind.CODEX,
        CompletionActivitySnapshot(10, CompletionActivityPhase.REQUESTING, 50),
      ),
    )
  }

  @Test
  fun `idle state explicitly distinguishes automatic and hotkey modes`() {
    val activity = CompletionActivitySnapshot(1, CompletionActivityPhase.IDLE, 0)

    assertEquals(
      "AI ○ idle · Codex: gpt-test",
      ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity),
    )
    assertEquals(
      "AI ⌨ hotkey · Codex: gpt-test",
      ProviderStatusPresentation.text(
        settings().apply { automaticEngine = AutomaticCompletionEngine.OFF.name },
        ProviderKind.CODEX,
        activity,
      ),
    )
  }

  @Test
  fun `active marker advances with the elapsed timer`() {
    val activity = CompletionActivitySnapshot(
      7,
      CompletionActivityPhase.REQUESTING,
      0,
      provider = ProviderKind.CODEX,
      startedAtMillis = 1_000,
    )

    assertTrue(ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity, 1_000).startsWith("AI ◐"))
    assertTrue(ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity, 1_250).startsWith("AI ◓"))
    assertTrue(ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity, 1_500).startsWith("AI ◑"))
    assertTrue(ProviderStatusPresentation.text(settings(), ProviderKind.CODEX, activity, 1_750).startsWith("AI ◒"))
  }

  private fun settings() = AutocompleteSettings.SettingsState(
    enabled = true,
    automaticEngine = AutomaticCompletionEngine.SELECTED_SUBSCRIPTION.name,
    provider = ProviderKind.CODEX.name,
    codexModel = "gpt-test",
  )
}
