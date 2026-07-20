package com.kkoemets.subscriptionautocomplete.context

import kotlin.test.Test
import kotlin.test.assertTrue

class ContextSourceBudgeterTest {
  @Test
  fun `source quotas never exceed the total budget`() {
    val selected = ContextSourceBudgeter.select(
      local = listOf(fragment("local", ContextFragmentSource.PSI_LOCAL)),
      recent = listOf(fragment("recent", ContextFragmentSource.RECENT_EDIT)),
      openTabs = listOf(fragment("open", ContextFragmentSource.OPEN_TAB)),
      totalTokens = 100,
    )

    assertTrue(selected.sumOf { TextBudget.estimateTokens(it.content) } <= 100)
    assertTrue(
      selected.filter { it.source == ContextFragmentSource.RECENT_EDIT }
        .sumOf { TextBudget.estimateTokens(it.content) } <= 30,
    )
    assertTrue(
      selected.filter { it.source == ContextFragmentSource.OPEN_TAB }
        .sumOf { TextBudget.estimateTokens(it.content) } <= 20,
    )
  }

  @Test
  fun `unused external budget returns to local context`() {
    val selected = ContextSourceBudgeter.select(
      local = listOf(fragment("local", ContextFragmentSource.PSI_LOCAL)),
      recent = emptyList(),
      openTabs = emptyList(),
      totalTokens = 100,
    )

    assertTrue(selected.single().content.length > 300)
  }

  private fun fragment(label: String, source: ContextFragmentSource) = ContextFragment(
    label = label,
    content = label.first().toString().repeat(800),
    priority = 100,
    maxTokens = 200,
    source = source,
  )
}
