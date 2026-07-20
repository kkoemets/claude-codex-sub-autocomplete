package com.kkoemets.subscriptionautocomplete.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderPolicyTest {
  @Test
  fun `uses the evaluated Spark low pair while retaining no-reasoning choices`() {
    assertEquals("gpt-5.3-codex-spark", ProviderPolicy.DEFAULT_CODEX_MODEL)
    assertTrue(ProviderPolicy.CODEX_SPARK_MODEL in ProviderPolicy.codexFallbackChoices)
    assertTrue("gpt-5.5" in ProviderPolicy.codexFallbackChoices)
    assertTrue("gpt-5.4" in ProviderPolicy.codexFallbackChoices)
    assertTrue("none" in ProviderPolicy.codexReasoningEfforts)
    assertEquals("low", ProviderPolicy.DEFAULT_CODEX_EFFORT)
    assertTrue(ProviderPolicy.DEFAULT_CODEX_MODEL !in ProviderPolicy.codexNoReasoningModels)
    assertTrue(ProviderPolicy.LEGACY_CODEX_MODEL in ProviderPolicy.codexNoReasoningModels)
  }
}
