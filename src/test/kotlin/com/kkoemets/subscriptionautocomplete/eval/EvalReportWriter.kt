package com.kkoemets.subscriptionautocomplete.eval

import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class EvalRunReport(
  val metadata: EvalRunMetadata,
  val completedAt: String,
  val results: List<EvalCaseResult>,
  val aggregate: EvalAggregate = EvalBaselineComparator.aggregate(results),
)

data class EvalReportPaths(
  val json: Path,
  val jsonLines: Path,
  val markdown: Path,
  val junitXml: Path,
)

object EvalReportWriter {
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val lineGson = GsonBuilder().disableHtmlEscaping().create()

  fun write(outputDirectory: Path, report: EvalRunReport): EvalReportPaths {
    Files.createDirectories(outputDirectory)
    val safeRunId = ReportRedactor.label(report.metadata.runId)
    val prefix = "eval-$safeRunId-${ReportRedactor.label(report.metadata.provider)}"
    val paths = EvalReportPaths(
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

  private fun markdown(report: EvalRunReport): String = buildString {
    appendLine("# Subscription autocomplete evaluation")
    appendLine()
    appendLine("Dataset: `${report.metadata.datasetVersion}`")
    appendLine()
    appendLine("Provider/model: `${report.metadata.provider}` / `${report.metadata.model}`")
    appendLine()
    appendLine("Reasoning effort: `${report.metadata.reasoningEffort}`")
    appendLine()
    appendLine("Profile: `${report.metadata.profile}`; advisory: `${report.metadata.advisory}`")
    appendLine()
    appendLine("| Cases | Passed | Exact | Median edit similarity | p50 | p95 |")
    appendLine("| ---: | ---: | ---: | ---: | ---: | ---: |")
    appendLine(
      "| ${report.aggregate.cases} | ${percent(report.aggregate.passRate)} | " +
        "${percent(report.aggregate.exactRate)} | ${"%.3f".format(report.aggregate.medianEditSimilarity)} | " +
        "${report.aggregate.latencyP50Millis} ms | ${report.aggregate.latencyP95Millis} ms |",
    )
    appendLine()
    appendLine("The artifact intentionally excludes prompts, source text, oracle targets, and raw suggestions.")
    appendLine()
    appendLine("## Cases")
    appendLine()
    appendLine("| ID | Surface | Kind | Phase | Result | Exact | Edit similarity | Latency | Error category |")
    appendLine("| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |")
    report.results.forEach { result ->
      appendLine(
        "| `${result.id}` | `${result.surface}` | `${result.kind}` | `${result.phase}` | " +
          "${if (result.passed) "PASS" else "FAIL"} | ${result.exactMatch ?: "-"} | " +
          "${result.editSimilarity ?: "-"} | ${result.durationMillis} ms | " +
          "${result.errorCategory ?: "-"} |",
      )
    }
  }

  private fun junitXml(report: EvalRunReport): String = buildString {
    val failures = report.results.count { !it.passed }
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append(
      "<testsuite name=\"subscription-autocomplete-${xml(report.metadata.provider)}\" " +
        "tests=\"${report.results.size}\" failures=\"$failures\">\n",
    )
    report.results.forEach { result ->
      append(
        "  <testcase classname=\"${xml(result.surface)}.${xml(result.kind.name)}\" " +
          "name=\"${xml(result.id)}\" time=\"${result.durationMillis / 1000.0}\">",
      )
      if (!result.passed) {
        append("<failure message=\"${xml(result.errorCategory ?: "metric-gate") }\"/>")
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
}

object ReportRedactor {
  fun label(value: String): String = value
    .replace(Regex("[^A-Za-z0-9._-]"), "-")
    .take(MAX_LABEL_LENGTH)
    .ifBlank { "unknown" }

  fun category(value: String): String = when {
    value.contains("timeout", ignoreCase = true) -> "timeout"
    value.contains("cancel", ignoreCase = true) -> "cancelled"
    value.contains("auth", ignoreCase = true) || value.contains("login", ignoreCase = true) -> "authentication"
    value.contains("syntax", ignoreCase = true) -> "syntax"
    value.contains("blank", ignoreCase = true) -> "blank"
    else -> "provider-failure"
  }

  private const val MAX_LABEL_LENGTH = 80
}
