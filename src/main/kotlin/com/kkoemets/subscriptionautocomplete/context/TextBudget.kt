package com.kkoemets.subscriptionautocomplete.context

import kotlin.math.max

object TextBudget {
  private const val CHARS_PER_TOKEN = 4

  fun estimateTokens(text: String): Int = max(1, (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)

  fun head(text: String, tokens: Int): String = text.take(tokens.coerceAtLeast(0) * CHARS_PER_TOKEN)

  fun tail(text: String, tokens: Int): String = text.takeLast(tokens.coerceAtLeast(0) * CHARS_PER_TOKEN)

  fun before(text: String, endOffset: Int, tokens: Int): String {
    val safeEnd = endOffset.coerceIn(0, text.length)
    val maxChars = tokens.coerceAtLeast(0) * CHARS_PER_TOKEN
    return text.substring((safeEnd - maxChars).coerceAtLeast(0), safeEnd)
  }

  fun after(text: String, startOffset: Int, tokens: Int): String {
    val safeStart = startOffset.coerceIn(0, text.length)
    val maxChars = tokens.coerceAtLeast(0) * CHARS_PER_TOKEN
    return text.substring(safeStart, (safeStart + maxChars).coerceAtMost(text.length))
  }

  fun around(text: String, offset: Int, tokens: Int): String {
    val maxChars = tokens.coerceAtLeast(0) * CHARS_PER_TOKEN
    if (text.length <= maxChars) return text
    val safeOffset = offset.coerceIn(0, text.length)
    val before = (maxChars * 2) / 3
    val start = (safeOffset - before).coerceAtLeast(0)
    val end = (start + maxChars).coerceAtMost(text.length)
    return text.substring((end - maxChars).coerceAtLeast(0), end)
  }

  fun trim(text: String, tokens: Int): String {
    val maxChars = tokens.coerceAtLeast(0) * CHARS_PER_TOKEN
    if (text.length <= maxChars) return text
    if (maxChars < 80) return text.take(maxChars)
    val marker = "\n... trimmed ...\n"
    val available = maxChars - marker.length
    val headChars = (available * 2) / 3
    return text.take(headChars) + marker + text.takeLast(available - headChars)
  }
}

object ContextBudgeter {
  fun select(fragments: List<ContextFragment>, tokenBudget: Int): List<ContextFragment> {
    val unique = fragments
      .asSequence()
      .filter { it.content.isNotBlank() }
      .sortedByDescending(ContextFragment::priority)
      .fold(emptyList<ContextFragment>()) { kept, fragment ->
        val normalized = fragment.content.replace(Regex("\\s+"), " ").trim()
        if (kept.any { existing ->
            val other = existing.content.replace(Regex("\\s+"), " ").trim()
            normalized == other || (normalized.length > 80 && other.contains(normalized))
          }
        ) {
          kept
        } else {
          kept + fragment
        }
      }

    return unique.fold(BudgetState(emptyList(), tokenBudget.coerceAtLeast(0))) { state, fragment ->
      if (state.remaining <= 0) return@fold state
      val allowed = minOf(fragment.maxTokens, state.remaining)
      val trimmed = TextBudget.trim(fragment.content, allowed).trim()
      val used = TextBudget.estimateTokens(trimmed)
      if (trimmed.isBlank() || used > state.remaining) {
        state
      } else {
        BudgetState(state.fragments + fragment.copy(content = trimmed), state.remaining - used)
      }
    }.fragments
  }

  private data class BudgetState(
    val fragments: List<ContextFragment>,
    val remaining: Int,
  )
}
