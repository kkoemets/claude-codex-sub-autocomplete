package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.google.gson.GsonBuilder
import com.kkoemets.subscriptionautocomplete.eval.EvalMetrics
import com.kkoemets.subscriptionautocomplete.eval.ReportRedactor
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

data class TerminalEvalRunMetadata(
  val runId: String,
  val startedAt: String,
  val datasetVersion: String,
  val provider: String,
  val model: String,
  val reasoningEffort: String,
  val profile: String,
  val randomSeed: Int,
  val warmRepetitions: Int,
  val advisory: Boolean,
  val suiteId: String,
  val caseSetSha256: String,
  val uniqueCases: Int,
  val attempts: Int,
  val promptPolicyVersion: String,
  val evaluatorVersion: String,
  val official: Boolean,
)

data class TerminalEvalAggregate(
  val cases: Int,
  val passed: Int,
  val passRate: Double,
  val exactRate: Double,
  val meanSemanticCoverage: Double,
  val latencyP50Millis: Long,
  val latencyP95Millis: Long,
)

data class TerminalEvalRunReport(
  val metadata: TerminalEvalRunMetadata,
  val completedAt: String,
  val results: List<TerminalEvalResult>,
  val aggregate: TerminalEvalAggregate = aggregate(results),
) {
  companion object {
    private fun aggregate(results: List<TerminalEvalResult>): TerminalEvalAggregate = TerminalEvalAggregate(
      cases = results.size,
      passed = results.count(TerminalEvalResult::passed),
      passRate = rate(results, TerminalEvalResult::passed),
      exactRate = rate(results, TerminalEvalResult::exactMatch),
      meanSemanticCoverage = results.map(TerminalEvalResult::semanticCoverage).average().takeUnless(Double::isNaN)
        ?: 0.0,
      latencyP50Millis = EvalMetrics.percentile(results.map(TerminalEvalResult::durationMillis), 0.50),
      latencyP95Millis = EvalMetrics.percentile(results.map(TerminalEvalResult::durationMillis), 0.95),
    )

    private fun rate(results: List<TerminalEvalResult>, predicate: (TerminalEvalResult) -> Boolean): Double =
      if (results.isEmpty()) 0.0 else results.count(predicate).toDouble() / results.size
  }
}

data class TerminalEvalReportPaths(
  val json: Path,
  val jsonLines: Path,
  val markdown: Path,
  val junitXml: Path,
)

object TerminalEvalReportWriter {
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val lineGson = GsonBuilder().disableHtmlEscaping().create()

  fun metadata(
    datasetVersion: String,
    provider: String,
    model: String,
    reasoningEffort: String,
    profile: String,
    randomSeed: Int,
    warmRepetitions: Int,
    advisory: Boolean,
    suiteId: String = "ad-hoc",
    caseSetSha256: String = "not-recorded",
    uniqueCases: Int = 0,
    attempts: Int = 0,
    promptPolicyVersion: String = PROMPT_POLICY_VERSION,
    evaluatorVersion: String = EVALUATOR_VERSION,
    official: Boolean = false,
    now: Instant = Instant.now(),
  ): TerminalEvalRunMetadata = TerminalEvalRunMetadata(
    runId = now.toString().replace(':', '-').substringBefore('.'),
    startedAt = now.toString(),
    datasetVersion = datasetVersion,
    provider = ReportRedactor.label(provider),
    model = ReportRedactor.label(model),
    reasoningEffort = ReportRedactor.label(reasoningEffort),
    profile = ReportRedactor.label(profile),
    randomSeed = randomSeed,
    warmRepetitions = warmRepetitions,
    advisory = advisory,
    suiteId = ReportRedactor.label(suiteId),
    caseSetSha256 = ReportRedactor.label(caseSetSha256),
    uniqueCases = uniqueCases,
    attempts = attempts,
    promptPolicyVersion = ReportRedactor.label(promptPolicyVersion),
    evaluatorVersion = ReportRedactor.label(evaluatorVersion),
    official = official,
  )

  fun caseSetSha256(caseIds: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(caseIds.joinToString("\n").toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
  }

  fun write(outputDirectory: Path, report: TerminalEvalRunReport): TerminalEvalReportPaths {
    Files.createDirectories(outputDirectory)
    val metadata = report.metadata
    val prefix = listOf("terminal", metadata.runId, metadata.provider, metadata.model, metadata.reasoningEffort)
      .joinToString("-") { ReportRedactor.label(it) }
    val paths = TerminalEvalReportPaths(
      json = outputDirectory.resolve("$prefix.json"),
      jsonLines = outputDirectory.resolve("$prefix.jsonl"),
      markdown = outputDirectory.resolve("$prefix.md"),
      junitXml = outputDirectory.resolve("$prefix.xml"),
    )
    Files.writeString(paths.json, gson.toJson(report), StandardCharsets.UTF_8)
    Files.writeString(
      paths.jsonLines,
      report.results.joinToString(separator = "\n", postfix = "\n", transform = lineGson::toJson),
      StandardCharsets.UTF_8,
    )
    Files.writeString(paths.markdown, markdown(report), StandardCharsets.UTF_8)
    Files.writeString(paths.junitXml, junitXml(report), StandardCharsets.UTF_8)
    return paths
  }

  private fun markdown(report: TerminalEvalRunReport): String = buildString {
    val aggregate = report.aggregate
    appendLine("# Terminal command evaluation")
    appendLine()
    appendLine("Dataset: `${report.metadata.datasetVersion}`")
    appendLine()
    appendLine(
      "Provider/model/effort: `${report.metadata.provider}` / `${report.metadata.model}` / " +
        "`${report.metadata.reasoningEffort}`",
    )
    appendLine()
    appendLine("Profile: `${report.metadata.profile}`; advisory: `${report.metadata.advisory}`")
    appendLine()
    appendLine(
      "Suite: `${report.metadata.suiteId}`; official: `${report.metadata.official}`; " +
        "unique cases: `${report.metadata.uniqueCases}`; attempts: `${report.metadata.attempts}`",
    )
    appendLine()
    appendLine(
      "Case-set SHA-256: `${report.metadata.caseSetSha256}`; prompt: " +
        "`${report.metadata.promptPolicyVersion}`; evaluator: `${report.metadata.evaluatorVersion}`",
    )
    appendLine()
    appendLine("| Cases | Passed | Exact | Semantic coverage | p50 | p95 |")
    appendLine("| ---: | ---: | ---: | ---: | ---: | ---: |")
    appendLine(
      "| ${aggregate.cases} | ${percent(aggregate.passRate)} | ${percent(aggregate.exactRate)} | " +
        "${percent(aggregate.meanSemanticCoverage)} | ${aggregate.latencyP50Millis} ms | " +
        "${aggregate.latencyP95Millis} ms |",
    )
    appendLine()
    appendLine("Reports exclude requests, reference commands, and generated commands.")
    appendLine()
    appendLine("| ID | Category | Phase | Result | Coverage | Syntax | Latency | Rule failures |")
    appendLine("| --- | --- | --- | --- | ---: | --- | ---: | --- |")
    report.results.forEach { result ->
      appendLine(
        "| `${result.id}` | `${result.category}` | `${result.phase}` | " +
          "${if (result.passed) "PASS" else "FAIL"} | ${percent(result.semanticCoverage)} | " +
          "`${result.syntaxOutcome}` | ${result.durationMillis} ms | " +
          "${result.failureRules.joinToString().ifBlank { "-" }} |",
      )
    }
  }

  private fun junitXml(report: TerminalEvalRunReport): String = buildString {
    val failures = report.results.count { !it.passed }
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<testsuite name=\"terminal-command-eval\" tests=\"${report.results.size}\" failures=\"$failures\">\n")
    report.results.forEach { result ->
      append(
        "  <testcase classname=\"terminal.${xml(result.category)}\" name=\"${xml(result.id)}\" " +
          "time=\"${result.durationMillis / 1000.0}\">",
      )
      if (!result.passed) {
        append("<failure message=\"${xml(result.failureRules.joinToString().ifBlank { "metric-gate" })}\"/>")
      }
      append("</testcase>\n")
    }
    append("</testsuite>\n")
  }

  private fun percent(value: Double): String = "%.1f%%".format(value * 100)

  private fun xml(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

  const val PROMPT_POLICY_VERSION = "terminal-command-v6"
  const val EVALUATOR_VERSION = "terminal-semantic-v11"
}
