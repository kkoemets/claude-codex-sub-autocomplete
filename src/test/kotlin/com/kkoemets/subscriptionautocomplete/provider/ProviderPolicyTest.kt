package com.kkoemets.subscriptionautocomplete.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderPolicyTest {
  @Test
  fun `defaults are Claude Haiku 5_5 and GPT-5_6 Luna with low reasoning`() {
    assertEquals("claude-haiku-5-5", ProviderPolicy.DEFAULT_CLAUDE_MODEL)
    assertEquals("gpt-5.6-luna", ProviderPolicy.DEFAULT_CODEX_MODEL)
    assertEquals("low", ProviderPolicy.DEFAULT_CODEX_EFFORT)
    assertEquals(ProviderPolicy.DEFAULT_CLAUDE_MODEL, ProviderPolicy.claudeModels.first())
    assertEquals(ProviderPolicy.DEFAULT_CODEX_MODEL, ProviderPolicy.codexModels.first())
    assertTrue(ProviderPolicy.DEFAULT_CODEX_EFFORT in ProviderPolicy.codexReasoningEfforts)
  }

  @Test
  fun `only models the subscriptions still serve are offered`() {
    assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), ProviderPolicy.claudeModels)
    assertEquals(listOf("gpt-5.6-luna", "gpt-5.6-terra"), ProviderPolicy.codexModels)
    assertEquals(listOf("none", "low", "medium", "high", "xhigh", "max"), ProviderPolicy.codexReasoningEfforts)
    ProviderPolicy.retiredCodexModels.forEach { retired ->
      assertFalse(retired in ProviderPolicy.codexModels, "$retired is retired but still offered")
    }
    assertEquals(
      setOf("gpt-5.3-codex-spark", "gpt-5.6-sol", "gpt-5.5", "gpt-5.4"),
      ProviderPolicy.retiredCodexModels,
    )
  }
}
