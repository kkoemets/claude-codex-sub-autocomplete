package com.kkoemets.subscriptionautocomplete.completion

/**
 * A local safety envelope for provider output, not a token counter.
 *
 * Completion tokens vary widely in character length, especially for formatted code. The configured
 * token value remains an approximate instruction to the provider; this larger envelope only prevents
 * a runaway response from consuming unbounded memory while allowing the requested completion to finish.
 */
internal object CompletionOutputEnvelope {
  fun maxCharacters(maxTokens: Int): Int =
    maxTokens.coerceIn(MIN_TOKENS, MAX_TOKENS) * SAFETY_CHARACTERS_PER_TOKEN

  fun failureMessage(provider: String, observedCharacters: Int, maxCharacters: Int): String =
    "$provider completion exceeded the local output safety envelope " +
      "($observedCharacters generated characters; $maxCharacters allowed). No partial suggestion was shown."

  private const val MIN_TOKENS = 1
  private const val MAX_TOKENS = 512
  private const val SAFETY_CHARACTERS_PER_TOKEN = 8
}
