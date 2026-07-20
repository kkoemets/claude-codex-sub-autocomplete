package com.kkoemets.subscriptionautocomplete.eval

data class EvalAggregate(
  val cases: Int,
  val passRate: Double,
  val exactRate: Double,
  val medianEditSimilarity: Double,
  val latencyP50Millis: Long,
  val latencyP95Millis: Long,
)

data class EvalBaselineComparison(
  val compatible: Boolean,
  val reason: String,
  val passRateDelta: Double? = null,
  val exactRateDelta: Double? = null,
  val medianEditSimilarityDelta: Double? = null,
  val latencyP50DeltaMillis: Long? = null,
  val latencyP95DeltaMillis: Long? = null,
)

object EvalBaselineComparator {
  fun compare(baseline: EvalRunReport, candidate: EvalRunReport): EvalBaselineComparison {
    val incompatibility = compatibilityFields(baseline.metadata, candidate.metadata)
      .firstOrNull { it.second.first != it.second.second }
    if (incompatibility != null) {
      return EvalBaselineComparison(false, "incompatible-${incompatibility.first}")
    }
    val before = aggregate(baseline.results)
    val after = aggregate(candidate.results)
    return EvalBaselineComparison(
      compatible = true,
      reason = "compatible",
      passRateDelta = after.passRate - before.passRate,
      exactRateDelta = after.exactRate - before.exactRate,
      medianEditSimilarityDelta = after.medianEditSimilarity - before.medianEditSimilarity,
      latencyP50DeltaMillis = after.latencyP50Millis - before.latencyP50Millis,
      latencyP95DeltaMillis = after.latencyP95Millis - before.latencyP95Millis,
    )
  }

  fun aggregate(results: List<EvalCaseResult>): EvalAggregate {
    val similarities = results.mapNotNull(EvalCaseResult::editSimilarity).sorted()
    return EvalAggregate(
      cases = results.size,
      passRate = EvalMetrics.passRate(results),
      exactRate = EvalMetrics.exactRate(results),
      medianEditSimilarity = similarities.median(),
      latencyP50Millis = EvalMetrics.percentile(results.map(EvalCaseResult::durationMillis), 0.50),
      latencyP95Millis = EvalMetrics.percentile(results.map(EvalCaseResult::durationMillis), 0.95),
    )
  }

  private fun compatibilityFields(
    baseline: EvalRunMetadata,
    candidate: EvalRunMetadata,
  ): List<Pair<String, Pair<Any, Any>>> = listOf(
    "dataset" to (baseline.datasetVersion to candidate.datasetVersion),
    "provider" to (baseline.provider to candidate.provider),
    "model" to (baseline.model to candidate.model),
    "reasoning-effort" to (baseline.reasoningEffort to candidate.reasoningEffort),
    "mode" to (baseline.completionMode to candidate.completionMode),
    "profile" to (baseline.profile to candidate.profile),
    "prompt-policy" to (baseline.promptPolicy to candidate.promptPolicy),
    "context-budget" to (baseline.contextTokenBudget to candidate.contextTokenBudget),
    "output-budget" to (baseline.maxOutputTokens to candidate.maxOutputTokens),
    "seed" to (baseline.randomSeed to candidate.randomSeed),
    "repetitions" to (baseline.warmRepetitions to candidate.warmRepetitions),
  )

  private fun List<Double>.median(): Double = when {
    isEmpty() -> 0.0
    size % 2 == 1 -> this[size / 2]
    else -> (this[size / 2 - 1] + this[size / 2]) / 2.0
  }
}
