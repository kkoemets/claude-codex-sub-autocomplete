package com.kkoemets.subscriptionautocomplete.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextBudgeterTest {
  @Test
  fun `trims oversized high priority fragments instead of discarding them`() {
    val selected = ContextBudgeter.select(
      listOf(
        ContextFragment("large", "x".repeat(4000), 100, 100, "test"),
        ContextFragment("small", "useful", 50, 20, "test"),
      ),
      120,
    )

    assertEquals(listOf("large", "small"), selected.map(ContextFragment::label))
    assertTrue(selected.sumOf { TextBudget.estimateTokens(it.content) } <= 120)
  }

  @Test
  fun `deduplicates equivalent fragments`() {
    val selected = ContextBudgeter.select(
      listOf(
        ContextFragment("one", "fun   hello()", 100, 40, "test"),
        ContextFragment("two", "fun hello()", 90, 40, "test"),
      ),
      100,
    )

    assertEquals(1, selected.size)
    assertEquals("one", selected.single().label)
  }
}
