package com.kkoemets.subscriptionautocomplete.eval

import kotlin.math.max

enum class EvalSyntaxOutcome {
  PASSED,
  REJECTED,
  SKIPPED,
  NOT_RUN,
}

data class EvalObservation(
  val candidate: String,
  val backendCalls: Int,
  val rendered: Boolean,
  val durationMillis: Long,
  val firstTokenMillis: Long? = null,
  val syntaxOutcome: EvalSyntaxOutcome = EvalSyntaxOutcome.NOT_RUN,
  val errorCategory: String? = null,
)

data class EvalCaseResult(
  val id: String,
  val surface: String,
  val kind: EvalTaskKind,
  val phase: String,
  val repetition: Int,
  val passed: Boolean,
  val exactMatch: Boolean?,
  val editSimilarity: Double?,
  val referencePrefixCoverage: Double?,
  val constraintCoverage: Double?,
  val overGeneratedCharacters: Int,
  val clean: Boolean,
  val suffixBoundaryPreserved: Boolean,
  val backendCalls: Int,
  val rendered: Boolean,
  val candidateCharacters: Int,
  val durationMillis: Long,
  val firstTokenMillis: Long?,
  val syntaxOutcome: EvalSyntaxOutcome,
  val errorCategory: String?,
)

object EvalMetrics {
  fun score(
    case: EvalCase,
    observation: EvalObservation,
    phase: String = "deterministic",
    repetition: Int = 0,
  ): EvalCaseResult {
    val candidate = normalize(observation.candidate)
    val reference = normalize(case.oracle.reference)
    val clean = FORBIDDEN_OUTPUT.none { pattern -> pattern.containsMatchIn(candidate) }
    val exact = reference.takeIf(String::isNotEmpty)?.let(candidate::equals)
    val similarity = reference.takeIf(String::isNotEmpty)?.let { editSimilarity(candidate, it) }
    val prefixCoverage = reference.takeIf(String::isNotEmpty)?.let {
      longestCommonPrefix(candidate, it).toDouble() / it.length
    }
    val constraintCoverage = case.oracle.expectedGroups.takeIf(List<*>::isNotEmpty)?.let { groups ->
      groups.count { alternatives ->
        alternatives.any { pattern -> Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(candidate) }
      }.toDouble() / groups.size
    }
    val overGenerated = max(0, candidate.length - reference.length)
    val suffixPreserved = exact == true || suffixBoundaryPreserved(candidate, normalize(case.input.suffix))
    val passed = when (case.kind) {
      EvalTaskKind.MASKED_SPAN ->
        observation.errorCategory == null && observation.rendered && clean && suffixPreserved && exact == true
      EvalTaskKind.ORDINARY,
      EvalTaskKind.RECENT_CURRENT_CONTEXT,
      -> observation.errorCategory == null && observation.rendered && clean && suffixPreserved &&
        observation.syntaxOutcome != EvalSyntaxOutcome.REJECTED &&
        candidate.length <= case.oracle.maxCharacters &&
        (constraintCoverage ?: similarity ?: 0.0) >= USEFUL_THRESHOLD
      EvalTaskKind.NEGATIVE -> when (case.oracle.negativeExpectation) {
        NegativeExpectation.NO_BACKEND_CALL -> observation.backendCalls == 0 && !observation.rendered
        NegativeExpectation.NO_RENDERED_SUGGESTION -> !observation.rendered
        null -> false
      }
    }
    return EvalCaseResult(
      id = case.id,
      surface = case.surface,
      kind = case.kind,
      phase = phase,
      repetition = repetition,
      passed = passed,
      exactMatch = exact,
      editSimilarity = similarity?.round(4),
      referencePrefixCoverage = prefixCoverage?.round(4),
      constraintCoverage = constraintCoverage?.round(4),
      overGeneratedCharacters = overGenerated,
      clean = clean,
      suffixBoundaryPreserved = suffixPreserved,
      backendCalls = observation.backendCalls,
      rendered = observation.rendered,
      candidateCharacters = candidate.length,
      durationMillis = observation.durationMillis,
      firstTokenMillis = observation.firstTokenMillis,
      syntaxOutcome = observation.syntaxOutcome,
      errorCategory = observation.errorCategory?.let(ReportRedactor::category),
    )
  }

  fun exactRate(results: List<EvalCaseResult>): Double = rate(
    results.filter { it.exactMatch != null },
  ) { it.exactMatch == true }

  fun passRate(results: List<EvalCaseResult>): Double = rate(results, EvalCaseResult::passed)

  fun percentile(values: List<Long>, percentile: Double): Long {
    if (values.isEmpty()) return 0
    val sorted = values.sorted()
    val position = ((sorted.size - 1) * percentile).toInt().coerceIn(sorted.indices)
    return sorted[position]
  }

  internal fun editSimilarity(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftCharacter ->
      val current = IntArray(right.length + 1)
      current[0] = leftIndex + 1
      right.forEachIndexed { rightIndex, rightCharacter ->
        val substitution = previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1
        current[rightIndex + 1] = minOf(
          current[rightIndex] + 1,
          previous[rightIndex + 1] + 1,
          substitution,
        )
      }
      previous = current
    }
    return 1.0 - previous.last().toDouble() / max(left.length, right.length)
  }

  private fun suffixBoundaryPreserved(candidate: String, suffix: String): Boolean {
    if (candidate.isEmpty() || suffix.isEmpty()) return true
    val maximum = minOf(candidate.length, suffix.length)
    return (1..maximum).none { overlap ->
      candidate.endsWith(suffix.take(overlap))
    }
  }

  private fun longestCommonPrefix(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    var index = 0
    while (index < limit && left[index] == right[index]) index += 1
    return index
  }

  private fun rate(results: List<EvalCaseResult>, predicate: (EvalCaseResult) -> Boolean): Double =
    if (results.isEmpty()) 0.0 else results.count(predicate).toDouble() / results.size

  private fun normalize(value: String): String = value.replace("\r\n", "\n")

  private fun Double.round(places: Int): Double {
    val factor = Math.pow(10.0, places.toDouble())
    return kotlin.math.round(this * factor) / factor
  }

  private const val USEFUL_THRESHOLD = 0.75
  private val FORBIDDEN_OUTPUT = listOf(
    Regex("```"),
    Regex("</?[^>\\n]*(?:cursor|code_(?:before|after))[^>\\n]*>", RegexOption.IGNORE_CASE),
    Regex("(?im)^[ \\t]*(?:the cursor\\b|the completion should\\b|wait,|here(?:'s| is)\\b|explanation:)"),
  )
}
