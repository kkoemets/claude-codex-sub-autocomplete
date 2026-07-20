package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.context.TextBudget
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionOutputEnvelopeTest {
  @Test
  fun `uses a code tolerant envelope without changing input context estimates`() {
    assertEquals(512, CompletionOutputEnvelope.maxCharacters(64))
    assertEquals(4_096, CompletionOutputEnvelope.maxCharacters(512))
    assertEquals(8, CompletionOutputEnvelope.maxCharacters(0))
    assertEquals(4_096, CompletionOutputEnvelope.maxCharacters(1_000))

    assertEquals(256, TextBudget.head("x".repeat(1_000), 64).length)
  }
}
