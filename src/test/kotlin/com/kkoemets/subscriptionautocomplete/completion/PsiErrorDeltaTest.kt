package com.kkoemets.subscriptionautocomplete.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PsiErrorDeltaTest {
  @Test
  fun `ignores equivalent pre-existing errors shifted by insertion`() {
    val baseline = listOf(SyntaxErrorFingerprint(12, 13, "OBJECT"))
    val candidate = listOf(SyntaxErrorFingerprint(16, 17, "OBJECT"))

    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline,
      candidate,
      insertionStart = 8,
      insertionLength = 4,
    )

    assertTrue(introduced.isEmpty())
  }

  @Test
  fun `reports a new error inside generated text`() {
    val error = SyntaxErrorFingerprint(10, 11, "VALUE")

    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline = emptyList(),
      candidate = listOf(error),
      insertionStart = 8,
      insertionLength = 4,
    )

    assertEquals(listOf(error), introduced)
  }

  @Test
  fun `ignores new errors unrelated to the insertion`() {
    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline = emptyList(),
      candidate = listOf(SyntaxErrorFingerprint(40, 41, "OTHER")),
      insertionStart = 8,
      insertionLength = 4,
    )

    assertTrue(introduced.isEmpty())
  }

  @Test
  fun `reports a zero width error at the suffix boundary`() {
    val error = SyntaxErrorFingerprint(12, 12, "VALUE")

    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline = emptyList(),
      candidate = listOf(error),
      insertionStart = 8,
      insertionLength = 4,
    )

    assertEquals(listOf(error), introduced)
  }

  @Test
  fun `does not report a shifted pre-existing suffix boundary error`() {
    val baseline = SyntaxErrorFingerprint(8, 8, "VALUE")
    val shifted = SyntaxErrorFingerprint(12, 12, "VALUE")

    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline = listOf(baseline),
      candidate = listOf(shifted),
      insertionStart = 8,
      insertionLength = 4,
    )

    assertTrue(introduced.isEmpty())
  }

  @Test
  fun `uses baseline errors as a multiset`() {
    val existing = SyntaxErrorFingerprint(8, 8, "OBJECT")
    val shifted = SyntaxErrorFingerprint(12, 12, "OBJECT")

    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline = listOf(existing),
      candidate = listOf(shifted, shifted),
      insertionStart = 8,
      insertionLength = 4,
    )

    assertEquals(listOf(shifted), introduced)
  }

  @Test
  fun `ignores an error ending immediately before generated text`() {
    val introduced = PsiErrorDelta.introducedNearInsertion(
      baseline = emptyList(),
      candidate = listOf(SyntaxErrorFingerprint(6, 8, "OTHER")),
      insertionStart = 8,
      insertionLength = 4,
    )

    assertTrue(introduced.isEmpty())
  }
}
