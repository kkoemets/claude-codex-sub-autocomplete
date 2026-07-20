package com.kkoemets.subscriptionautocomplete.eval

import java.time.Instant

data class EvalRunMetadata(
  val runId: String,
  val startedAt: String,
  val datasetVersion: String,
  val provider: String,
  val model: String,
  val reasoningEffort: String,
  val completionMode: String,
  val profile: String,
  val promptPolicy: String,
  val contextTokenBudget: Int,
  val maxOutputTokens: Int,
  val randomSeed: Int,
  val warmRepetitions: Int,
  val osFamily: String,
  val javaMajor: Int,
  val ideBuild: String,
  val pluginVersion: String,
  val advisory: Boolean,
)

object EvalEnvironment {
  fun capture(
    datasetVersion: String,
    provider: String,
    model: String,
    reasoningEffort: String = "not-applicable",
    completionMode: String,
    profile: String,
    contextTokenBudget: Int,
    maxOutputTokens: Int,
    randomSeed: Int,
    warmRepetitions: Int = 1,
    advisory: Boolean,
    now: Instant = Instant.now(),
  ): EvalRunMetadata = EvalRunMetadata(
    runId = now.toString().replace(':', '-').substringBefore('.'),
    startedAt = now.toString(),
    datasetVersion = datasetVersion,
    provider = ReportRedactor.label(provider),
    model = ReportRedactor.label(model),
    reasoningEffort = ReportRedactor.label(reasoningEffort),
    completionMode = ReportRedactor.label(completionMode),
    profile = ReportRedactor.label(profile),
    promptPolicy = PROMPT_POLICY,
    contextTokenBudget = contextTokenBudget,
    maxOutputTokens = maxOutputTokens,
    randomSeed = randomSeed,
    warmRepetitions = warmRepetitions,
    osFamily = osFamily(System.getProperty("os.name", "unknown")),
    javaMajor = Runtime.version().feature(),
    ideBuild = ReportRedactor.label(System.getProperty("idea.build.number", "unknown")),
    pluginVersion = ReportRedactor.label(System.getProperty("subscriptionAutocomplete.version", "unknown")),
    advisory = advisory,
  )

  private fun osFamily(value: String): String = when {
    value.contains("mac", ignoreCase = true) -> "macOS"
    value.contains("win", ignoreCase = true) -> "Windows"
    value.contains("linux", ignoreCase = true) -> "Linux"
    else -> "Other"
  }

  const val PROMPT_POLICY = "completion-prompt-v1"
}
