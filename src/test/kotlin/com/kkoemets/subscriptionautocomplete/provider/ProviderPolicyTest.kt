package com.kkoemets.subscriptionautocomplete.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderPolicyTest {
  @Test
  fun `uses the pinned Luna low pair while retaining explicit model choices`() {
    assertEquals("gpt-5.6-luna", ProviderPolicy.DEFAULT_CODEX_MODEL)
    assertTrue(ProviderPolicy.CODEX_SPARK_MODEL in ProviderPolicy.codexFallbackChoices)
    assertTrue("gpt-5.5" in ProviderPolicy.codexFallbackChoices)
    assertTrue("gpt-5.4" in ProviderPolicy.codexFallbackChoices)
    assertTrue("none" in ProviderPolicy.codexReasoningEfforts)
    assertEquals("low", ProviderPolicy.DEFAULT_CODEX_EFFORT)
    assertTrue(ProviderPolicy.LEGACY_CODEX_MODEL in ProviderPolicy.codexNoReasoningModels)
  }
}
