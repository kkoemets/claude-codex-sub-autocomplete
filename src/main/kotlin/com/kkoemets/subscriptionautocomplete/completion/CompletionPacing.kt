package com.kkoemets.subscriptionautocomplete.completion

import java.util.concurrent.ConcurrentHashMap

internal class CompletionPacing(
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val states = ConcurrentHashMap<String, State>()

  fun recordCancellation(key: String) {
    val now = clock()
    prune(now)
    states.compute(key) { _, previous ->
      val cancellations = if (previous == null || now - previous.updatedAt > RESET_AFTER_MILLIS) {
        1
      } else {
        previous.cancellations + 1
      }
      State(cancellations.coerceAtMost(MAX_CANCELLATIONS), now)
    }
  }

  fun recordTerminalResult(key: String) {
    states.remove(key)
  }

  fun debounceMillis(key: String, configuredMillis: Int, fastBoundary: Boolean): Int {
    val base = configuredMillis.coerceIn(100, 3000)
    if (fastBoundary) return minOf(base, FAST_BOUNDARY_MILLIS)
    val state = states[key] ?: return base
    if (clock() - state.updatedAt > RESET_AFTER_MILLIS) {
      states.remove(key, state)
      return base
    }
    return (base + state.cancellations * CANCELLATION_BACKOFF_MILLIS).coerceAtMost(3000)
  }

  fun clear() {
    states.clear()
  }

  private fun prune(now: Long) {
    states.entries.removeIf { now - it.value.updatedAt > RESET_AFTER_MILLIS }
    if (states.size < MAX_STATES) return
    states.entries.minByOrNull { it.value.updatedAt }?.let { oldest ->
      states.remove(oldest.key, oldest.value)
    }
  }

  private data class State(val cancellations: Int, val updatedAt: Long)

  private companion object {
    const val FAST_BOUNDARY_MILLIS = 350
    const val CANCELLATION_BACKOFF_MILLIS = 150
    const val MAX_CANCELLATIONS = 6
    const val RESET_AFTER_MILLIS = 10_000L
    const val MAX_STATES = 256
  }
}

internal object CompletionTriggerPolicy {
  fun shouldRequest(text: CharSequence, caretOffset: Int, typed: String): Boolean {
    val safeOffset = caretOffset.coerceIn(0, text.length)
    if (typed.isEmpty() || typed == "\b") return false
    if (typed.contains('\n')) return true
    val suffix = text.getOrNull(safeOffset)
    if (suffix != null && (suffix.isLetterOrDigit() || suffix == '_')) return false
    val lineStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val linePrefix = text.substring(lineStart, safeOffset)
    val trimmed = linePrefix.trimStart()
    if (trimmed.isBlank()) return false
    if (isComment(trimmed)) return trimmed.length >= MIN_COMMENT_CHARS
    if (typed.last() in FAST_BOUNDARIES) return true
    val identifierLength = linePrefix.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }.length
    return identifierLength >= MIN_IDENTIFIER_CHARS
  }

  fun isFastBoundary(typed: String): Boolean = typed.contains('\n') || typed.lastOrNull() in FAST_BOUNDARIES

  private fun isComment(trimmedLine: String): Boolean =
    COMMENT_PREFIXES.any(trimmedLine::startsWith) || trimmedLine.contains("/*") || trimmedLine.contains("<!--")

  private val COMMENT_PREFIXES = listOf("//", "#", "--", "*")
  private val FAST_BOUNDARIES = setOf(' ', '.', '=', '(', '{', '[', ',', ':', '>', ')')
  private const val MIN_IDENTIFIER_CHARS = 3
  private const val MIN_COMMENT_CHARS = 6
}

internal object CompletionLimits {
  fun contextTokens(
    configured: Int,
    mode: CompletionMode,
    intent: CompletionIntent = CompletionIntent.ORDINARY,
  ): Int = when (mode) {
    CompletionMode.AUTOMATIC -> minOf(configured, when (intent) {
      CompletionIntent.ORDINARY -> AUTOMATIC_CONTEXT_TOKENS
      CompletionIntent.MULTILINE_IMPLEMENTATION -> MULTILINE_CONTEXT_TOKENS
      CompletionIntent.COMPLEX_IMPLEMENTATION -> COMPLEX_CONTEXT_TOKENS
    })
    CompletionMode.MANUAL -> configured
  }

  fun outputTokens(
    configured: Int,
    mode: CompletionMode,
    intent: CompletionIntent = CompletionIntent.ORDINARY,
  ): Int = when (mode) {
    CompletionMode.AUTOMATIC -> minOf(configured, when (intent) {
      CompletionIntent.ORDINARY -> AUTOMATIC_OUTPUT_TOKENS
      CompletionIntent.MULTILINE_IMPLEMENTATION -> MULTILINE_OUTPUT_TOKENS
      CompletionIntent.COMPLEX_IMPLEMENTATION -> COMPLEX_OUTPUT_TOKENS
    })
    CompletionMode.MANUAL -> configured
  }

  private const val AUTOMATIC_CONTEXT_TOKENS = 800
  private const val AUTOMATIC_OUTPUT_TOKENS = 64
  private const val MULTILINE_CONTEXT_TOKENS = 1_200
  private const val MULTILINE_OUTPUT_TOKENS = 192
  private const val COMPLEX_CONTEXT_TOKENS = 1_400
  private const val COMPLEX_OUTPUT_TOKENS = 512
}
