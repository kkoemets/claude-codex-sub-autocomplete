package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutomaticCompletionEngine
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompletionEngineResolverTest {
  private val resolver = CompletionEngineResolver()

  @Test
  fun `manual completion always uses the selected subscription provider`() {
    val settings = AutocompleteSettings.SettingsState(
      provider = ProviderKind.CLAUDE.name,
      automaticEngine = AutomaticCompletionEngine.OFF.name,
    )

    val engine = resolver.resolve(CompletionMode.MANUAL, settings)

    assertEquals(CompletionEngineId.CLAUDE_SUBSCRIPTION, engine?.id)
    assertEquals(CompletionDestination.SUBSCRIPTION_PROCESS, engine?.destination)
  }

  @Test
  fun `automatic completion is disabled independently of manual provider`() {
    val settings = AutocompleteSettings.SettingsState(
      provider = ProviderKind.CODEX.name,
      automaticEngine = AutomaticCompletionEngine.OFF.name,
    )

    assertNull(resolver.resolve(CompletionMode.AUTOMATIC, settings))
  }

  @Test
  fun `automatic subscription completion uses the selected provider`() {
    val settings = AutocompleteSettings.SettingsState(
      provider = ProviderKind.CODEX.name,
      automaticEngine = AutomaticCompletionEngine.SELECTED_SUBSCRIPTION.name,
    )

    assertEquals(
      CompletionEngineId.CODEX_SUBSCRIPTION,
      resolver.resolve(CompletionMode.AUTOMATIC, settings)?.id,
    )
  }

  @Test
  fun `bundled mode never resolves to a subscription provider`() {
    val settings = AutocompleteSettings.SettingsState(
      provider = ProviderKind.CLAUDE.name,
      automaticEngine = AutomaticCompletionEngine.BUNDLED_FIM.name,
    )

    val engine = resolver.resolve(CompletionMode.AUTOMATIC, settings)

    assertEquals(CompletionEngineId.BUNDLED_FIM, engine?.id)
    assertNull(engine?.provider)
    assertEquals(CompletionDestination.BUNDLED_LOCAL_PROCESS, engine?.destination)
  }
}
