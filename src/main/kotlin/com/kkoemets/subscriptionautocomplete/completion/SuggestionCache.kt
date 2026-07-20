package com.kkoemets.subscriptionautocomplete.completion

import java.util.ArrayDeque

internal data class SuggestionCacheKey(
  val filePath: String,
  val engine: CompletionEngineId,
  val model: String,
  val settingsRevision: Long,
  val contextSharingRevision: String,
)

internal data class SuggestionAnchor(
  val anchorOffset: Int,
  val prefixTail: String,
  val suffixHead: String,
)

internal class SuggestionCache(
  private val maxEntries: Int = 64,
  private val maxAgeMillis: Long = 120_000,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val entries = ArrayDeque<Entry>()

  fun put(
    key: SuggestionCacheKey,
    documentText: CharSequence,
    anchorOffset: Int,
    completion: String,
  ) = put(key, capture(documentText, anchorOffset), completion)

  fun put(
    key: SuggestionCacheKey,
    anchor: SuggestionAnchor,
    completion: String,
  ) {
    if (completion.isBlank()) return
    val entry = Entry(
      key = key,
      anchorOffset = anchor.anchorOffset,
      prefixTail = anchor.prefixTail,
      suffixHead = anchor.suffixHead,
      completion = completion,
      createdAt = clock(),
    )
    synchronized(entries) {
      evictExpired(entry.createdAt)
      entries.removeIf { existing ->
        existing.key == key && existing.anchorOffset == anchor.anchorOffset
      }
      entries.addFirst(entry)
      while (entries.size > maxEntries.coerceAtLeast(1)) entries.removeLast()
    }
  }

  fun remaining(
    key: SuggestionCacheKey,
    documentText: CharSequence,
    caretOffset: Int,
  ): String? = synchronized(entries) {
    val now = clock()
    evictExpired(now)
    entries.firstNotNullOfOrNull { entry -> entry.remaining(key, documentText, caretOffset) }
  }

  fun clear() = synchronized(entries) {
    entries.clear()
  }

  private fun evictExpired(now: Long) {
    entries.removeIf { now - it.createdAt > maxAgeMillis }
  }

  fun capture(documentText: CharSequence, anchorOffset: Int): SuggestionAnchor {
    val safeOffset = anchorOffset.coerceIn(0, documentText.length)
    return SuggestionAnchor(
      anchorOffset = safeOffset,
      prefixTail = documentText.subSequence(
        (safeOffset - MATCH_CHARS).coerceAtLeast(0),
        safeOffset,
      ).toString(),
      suffixHead = documentText.subSequence(
        safeOffset,
        (safeOffset + MATCH_CHARS).coerceAtMost(documentText.length),
      ).toString(),
    )
  }

  private data class Entry(
    val key: SuggestionCacheKey,
    val anchorOffset: Int,
    val prefixTail: String,
    val suffixHead: String,
    val completion: String,
    val createdAt: Long,
  ) {
    fun remaining(key: SuggestionCacheKey, text: CharSequence, caretOffset: Int): String? {
      if (this.key != key || caretOffset !in anchorOffset..(anchorOffset + completion.length)) return null
      if (anchorOffset > text.length || caretOffset > text.length) return null
      val currentPrefixTail = text.subSequence(
        (anchorOffset - MATCH_CHARS).coerceAtLeast(0),
        anchorOffset,
      ).toString()
      if (currentPrefixTail != prefixTail) return null
      val typedLength = caretOffset - anchorOffset
      if (text.subSequence(anchorOffset, caretOffset).toString() != completion.take(typedLength)) return null
      if (caretOffset + suffixHead.length > text.length) return null
      if (text.subSequence(caretOffset, caretOffset + suffixHead.length).toString() != suffixHead) return null
      return completion.drop(typedLength).takeIf(String::isNotEmpty)
    }
  }

  private companion object {
    const val MATCH_CHARS = 256
  }
}
