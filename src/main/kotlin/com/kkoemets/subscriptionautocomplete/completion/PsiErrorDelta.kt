package com.kkoemets.subscriptionautocomplete.completion

internal object PsiErrorDelta {
  fun introducedNearInsertion(
    baseline: List<SyntaxErrorFingerprint>,
    candidate: List<SyntaxErrorFingerprint>,
    insertionStart: Int,
    insertionLength: Int,
  ): List<SyntaxErrorFingerprint> {
    val insertionEnd = insertionStart + insertionLength
    val remainingBaseline = baseline.groupingBy { it }.eachCount().toMutableMap()
    return candidate.filter { error ->
      val mapped = mapToBaseline(error, insertionStart, insertionEnd, insertionLength)
      val existingCount = mapped?.let { remainingBaseline[it] }.orZero()
      val existed = existingCount > 0
      if (mapped != null && existed) {
        if (existingCount == 1) remainingBaseline.remove(mapped) else remainingBaseline[mapped] = existingCount - 1
      }
      !existed && intersectsGeneratedOrSuffixBoundary(error, insertionStart, insertionEnd)
    }
  }

  private fun Int?.orZero(): Int = this ?: 0

  private fun mapToBaseline(
    error: SyntaxErrorFingerprint,
    insertionStart: Int,
    insertionEnd: Int,
    insertionLength: Int,
  ): SyntaxErrorFingerprint? = when {
    error.endOffset <= insertionStart -> error
    error.startOffset >= insertionEnd -> error.copy(
      startOffset = error.startOffset - insertionLength,
      endOffset = error.endOffset - insertionLength,
    )
    else -> null
  }

  private fun intersectsGeneratedOrSuffixBoundary(
    error: SyntaxErrorFingerprint,
    insertionStart: Int,
    insertionEnd: Int,
  ): Boolean = when {
    error.startOffset == error.endOffset -> error.startOffset in insertionStart..insertionEnd
    else -> error.startOffset <= insertionEnd && error.endOffset > insertionStart
  }
}
