package com.kkoemets.subscriptionautocomplete.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuggestionCacheTest {
  private val key = SuggestionCacheKey(
    "/project/main.kt",
    CompletionEngineId.CODEX_SUBSCRIPTION,
    "model",
    settingsRevision = 1,
    contextSharingRevision = "subscription:false",
  )

  @Test
  fun `reuses the untyped tail of a matching completion`() {
    val cache = SuggestionCache()
    cache.put(key, "val answer = \nprintln()", 13, "computeValue()")

    assertEquals(
      "puteValue()",
      cache.remaining(key, "val answer = com\nprintln()", 16),
    )
  }

  @Test
  fun `rejects a completion after unrelated typing`() {
    val cache = SuggestionCache()
    cache.put(key, "val answer = \nprintln()", 13, "computeValue()")

    assertNull(cache.remaining(key, "val answer = other\nprintln()", 18))
  }

  @Test
  fun `expires cached completions`() {
    var now = 1_000L
    val cache = SuggestionCache(maxAgeMillis = 100, clock = { now })
    cache.put(key, "val answer = ", 13, "computeValue()")
    now += 101

    assertNull(cache.remaining(key, "val answer = ", 13))
  }

  @Test
  fun `clears all cached completions`() {
    val cache = SuggestionCache()
    cache.put(key, "val answer = ", 13, "computeValue()")

    cache.clear()

    assertNull(cache.remaining(key, "val answer = ", 13))
  }
}
