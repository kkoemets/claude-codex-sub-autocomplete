package com.kkoemets.subscriptionautocomplete.eval.terminal

data class TerminalEvalCase(
  val id: String,
  val category: String,
  val request: String,
  val reference: String,
  val shell: String,
  val platform: String,
  val projectName: String,
  val projectMarkers: List<String>,
  val requiredGroups: List<List<String>>,
  val forbiddenFragments: List<String>,
  val validator: String?,
) {
  val description: String
    get() = request.trim().removePrefix("#").trim()
}

data class TerminalEvalDataset(
  val version: String,
  val cases: List<TerminalEvalCase>,
) {
  fun casesForCategory(category: String): List<TerminalEvalCase> = cases.filter { it.category == category }
}

enum class TerminalSyntaxOutcome {
  PASSED,
  REJECTED,
  SKIPPED,
}

data class TerminalEvalObservation(
  val candidate: String,
  val durationMillis: Long,
  val firstTokenMillis: Long? = null,
  val syntaxOutcome: TerminalSyntaxOutcome = TerminalSyntaxOutcome.SKIPPED,
  val error: String? = null,
)

data class TerminalEvalResult(
  val id: String,
  val category: String,
  val phase: String,
  val repetition: Int,
  val passed: Boolean,
  val exactMatch: Boolean,
  val semanticCoverage: Double,
  val syntaxOutcome: TerminalSyntaxOutcome,
  val candidateCharacters: Int,
  val durationMillis: Long,
  val firstTokenMillis: Long?,
  val failureRules: List<String>,
  val errorCategory: String?,
)
