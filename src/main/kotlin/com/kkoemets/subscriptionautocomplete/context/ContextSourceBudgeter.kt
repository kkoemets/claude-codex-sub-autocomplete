package com.kkoemets.subscriptionautocomplete.context

object ContextSourceBudgeter {
  fun select(
    local: List<ContextFragment>,
    recent: List<ContextFragment>,
    openTabs: List<ContextFragment>,
    totalTokens: Int,
  ): List<ContextFragment> {
    val safeTotal = totalTokens.coerceAtLeast(0)
    val recentBudget = (safeTotal * 30) / 100
    val openBudget = (safeTotal * 20) / 100
    val selectedRecent = ContextBudgeter.select(recent, recentBudget)
    val selectedOpen = ContextBudgeter.select(openTabs, openBudget)
    val externalUsed = (selectedRecent + selectedOpen).sumOf { TextBudget.estimateTokens(it.content) }
    val selectedLocal = ContextBudgeter.select(local, (safeTotal - externalUsed).coerceAtLeast(0))
    return selectedLocal + selectedRecent + selectedOpen
  }
}
