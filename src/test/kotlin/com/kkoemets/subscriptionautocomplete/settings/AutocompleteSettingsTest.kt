package com.kkoemets.subscriptionautocomplete.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutocompleteSettingsTest {
  @Test
  fun `new settings use the low-latency Spark pair by default`() {
    val state = AutocompleteSettings.SettingsState()

    assertEquals("gpt-5.3-codex-spark", state.codexModel)
    assertEquals("low", state.codexReasoningEffort)
  }

  @Test
  fun `new settings use the evaluated context and completion ceilings`() {
    val state = AutocompleteSettings.SettingsState()

    assertEquals(1400, state.contextTokenBudget)
    assertEquals(512, state.maxOutputTokens)
  }

  @Test
  fun `new settings expose terminal commands behind an explicit hash trigger`() {
    val state = AutocompleteSettings.SettingsState()

    assertTrue(state.terminalCompletionsEnabled)
  }

  @Test
  fun `pre-terminal settings migrate terminal commands on`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 5,
        terminalCompletionsEnabled = false,
      ),
    )

    assertTrue(settings.state.terminalCompletionsEnabled)
  }

  @Test
  fun `existing automatic settings preserve subscription automatic completion`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 0,
        provider = ProviderKind.CODEX.name,
        manualOnly = false,
      ),
    )

    assertFalse(settings.state.manualOnly)
    assertEquals(AutomaticCompletionEngine.SELECTED_SUBSCRIPTION, settings.selectedAutomaticEngine())
  }

  @Test
  fun `existing manual settings migrate to automatic engine off`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 1,
        manualOnly = true,
      ),
    )

    assertTrue(settings.state.manualOnly)
    assertEquals(AutomaticCompletionEngine.OFF, settings.selectedAutomaticEngine())
  }

  @Test
  fun `updates increment the settings revision`() {
    val settings = AutocompleteSettings()
    val revision = settings.state.settingsRevision

    settings.update { it.debounceMs = 900 }

    assertEquals(revision + 1, settings.state.settingsRevision)
  }

  @Test
  fun `legacy default output budget migrates for complex implementation intents`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 2,
        maxOutputTokens = 192,
      ),
    )

    assertEquals(512, settings.state.maxOutputTokens)
  }

  @Test
  fun `custom legacy output budget is preserved`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 2,
        maxOutputTokens = 128,
      ),
    )

    assertEquals(128, settings.state.maxOutputTokens)
  }

  @Test
  fun `legacy Luna low default migrates to the evaluated Spark pair`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 3,
        codexModel = "gpt-5.6-luna",
        codexReasoningEffort = "low",
      ),
    )

    assertEquals("gpt-5.3-codex-spark", settings.state.codexModel)
    assertEquals("low", settings.state.codexReasoningEffort)
  }

  @Test
  fun `legacy Spark reasoning remains low because none is unsupported`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 3,
        codexModel = "gpt-5.3-codex-spark",
        codexReasoningEffort = "low",
      ),
    )

    assertEquals("low", settings.state.codexReasoningEffort)
  }

  @Test
  fun `previous Luna defaults migrate to the evaluated Spark pair`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 4,
        codexModel = "gpt-5.6-luna",
        codexReasoningEffort = "none",
      ),
    )

    assertEquals("gpt-5.3-codex-spark", settings.state.codexModel)
    assertEquals("low", settings.state.codexReasoningEffort)
  }

  @Test
  fun `custom no-reasoning Codex model is preserved during Spark migration`() {
    val settings = AutocompleteSettings()

    settings.loadState(
      AutocompleteSettings.SettingsState(
        settingsVersion = 4,
        codexModel = "gpt-5.5",
        codexReasoningEffort = "none",
      ),
    )

    assertEquals("gpt-5.5", settings.state.codexModel)
    assertEquals("none", settings.state.codexReasoningEffort)
  }
}
