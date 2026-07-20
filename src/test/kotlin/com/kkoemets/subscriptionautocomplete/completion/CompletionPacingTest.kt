package com.kkoemets.subscriptionautocomplete.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionPacingTest {
  @Test
  fun `backs off repeatedly cancelled automatic requests`() {
    val pacing = CompletionPacing()

    pacing.recordCancellation("file")
    pacing.recordCancellation("file")

    assertEquals(1_050, pacing.debounceMillis("file", 750, fastBoundary = false))
    assertEquals(350, pacing.debounceMillis("file", 750, fastBoundary = true))
    pacing.recordTerminalResult("file")
    assertEquals(750, pacing.debounceMillis("file", 750, fastBoundary = false))
  }

  @Test
  fun `trigger gate accepts comments identifiers and semantic boundaries`() {
    assertTrue(CompletionTriggerPolicy.shouldRequest("// explain retry", 16, "y"))
    assertTrue(CompletionTriggerPolicy.shouldRequest("const result = ", 15, " "))
    assertTrue(CompletionTriggerPolicy.shouldRequest("ret", 3, "t"))
    assertTrue(CompletionTriggerPolicy.shouldRequest("value\n", 6, "\n"))
  }

  @Test
  fun `trigger gate rejects blank lines and edits in the middle of identifiers`() {
    assertFalse(CompletionTriggerPolicy.shouldRequest("  ", 2, " "))
    assertFalse(CompletionTriggerPolicy.shouldRequest("returnValue", 3, "t"))
  }

  @Test
  fun `automatic limits stay small while manual limits remain configurable`() {
    assertEquals(800, CompletionLimits.contextTokens(1_400, CompletionMode.AUTOMATIC))
    assertEquals(64, CompletionLimits.outputTokens(192, CompletionMode.AUTOMATIC))
    assertEquals(1_400, CompletionLimits.contextTokens(1_400, CompletionMode.MANUAL))
    assertEquals(192, CompletionLimits.outputTokens(192, CompletionMode.MANUAL))
  }

  @Test
  fun `specific previous-line intent receives bounded multiline limits`() {
    val intent = CompletionIntentClassifier.classify("// bubble sort\n", languageId = "TypeScript")

    assertEquals(CompletionIntent.MULTILINE_IMPLEMENTATION, intent)
    assertEquals(1_200, CompletionLimits.contextTokens(1_400, CompletionMode.AUTOMATIC, intent))
    assertEquals(192, CompletionLimits.outputTokens(192, CompletionMode.AUTOMATIC, intent))
    assertEquals(160, CompletionLimits.outputTokens(160, CompletionMode.AUTOMATIC, intent))
  }

  @Test
  fun `multiline limits never exceed configured budgets`() {
    val intent = CompletionIntent.MULTILINE_IMPLEMENTATION

    assertEquals(900, CompletionLimits.contextTokens(900, CompletionMode.AUTOMATIC, intent))
    assertEquals(128, CompletionLimits.outputTokens(128, CompletionMode.AUTOMATIC, intent))
  }

  @Test
  fun `contiguous LRU instruction block receives complex implementation limits`() {
    val intent = CompletionIntentClassifier.classify(
      "// Implement an LRU (Least Recently Used) Cache class with O(1) time complexity for both get and put operations.\n" +
        "// It should use a Hash Map and a Doubly Linked List under the hood.\n",
      languageId = "TypeScript",
    )

    assertEquals(CompletionIntent.COMPLEX_IMPLEMENTATION, intent)
    assertEquals(1_400, CompletionLimits.contextTokens(1_400, CompletionMode.AUTOMATIC, intent))
    assertEquals(512, CompletionLimits.outputTokens(512, CompletionMode.AUTOMATIC, intent))
    assertEquals(256, CompletionLimits.outputTokens(256, CompletionMode.AUTOMATIC, intent))
  }

  @Test
  fun `multiline prose and unrelated follow-up comments remain ordinary`() {
    val prose = CompletionIntentClassifier.classify(
      "// This cache keeps recently viewed records.\n// It should remain bounded in memory.\n",
      languageId = "TypeScript",
    )
    val unrelated = CompletionIntentClassifier.classify(
      "// Implement an LRU cache class.\n// Yesterday this was discussed with the team.\n",
      languageId = "TypeScript",
    )

    assertEquals(CompletionIntent.ORDINARY, prose)
    assertEquals(CompletionIntent.ORDINARY, unrelated)
  }

  @Test
  fun `prose comments and non-adjacent intents keep ordinary automatic limits`() {
    val prose = CompletionIntentClassifier.classify(
      "// This method keeps results stable.\n",
      languageId = "TypeScript",
    )
    val separated = CompletionIntentClassifier.classify(
      "// bubble sort\n\n",
      languageId = "TypeScript",
    )

    assertEquals(CompletionIntent.ORDINARY, prose)
    assertEquals(CompletionIntent.ORDINARY, separated)
    assertEquals(64, CompletionLimits.outputTokens(192, CompletionMode.AUTOMATIC, prose))
  }

  @Test
  fun `intent classification is limited to the blank line created by enter`() {
    assertEquals(
      CompletionIntent.ORDINARY,
      CompletionIntentClassifier.classify(
        "# implement binary search\ndef binary_search(",
        languageId = "Python",
      ),
    )
    assertEquals(
      CompletionIntent.ORDINARY,
      CompletionIntentClassifier.classify(
        "// bubble sort\n// continue explaining",
        languageId = "TypeScript",
      ),
    )
  }

  @Test
  fun `descriptive verb phrases and unknown languages fail closed`() {
    listOf(
      "// parse results are cached\n",
      "// build status is displayed\n",
      "// sort order is ascending\n",
    ).forEach { prefix ->
      assertEquals(
        CompletionIntent.ORDINARY,
        CompletionIntentClassifier.classify(prefix, languageId = "TypeScript"),
      )
    }
    assertEquals(
      CompletionIntent.ORDINARY,
      CompletionIntentClassifier.classify("// bubble sort\n", languageId = "unknown"),
    )
  }
}
