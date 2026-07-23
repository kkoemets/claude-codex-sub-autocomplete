package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandPromptBuilder
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandSanitizer
import com.kkoemets.subscriptionautocomplete.terminal.TerminalPromptContext
import java.nio.file.Path
import java.time.Instant

object DeterministicTerminalEval {
  @JvmStatic
  fun main(args: Array<String>) {
    val startedAt = Instant.now()
    val dataset = TerminalEvalDatasetLoader.load()
    val results = dataset.cases.map { case ->
      val caseStartedAt = System.nanoTime()
      val prompt = prompt(case)
      requireOracleAbsent(case, prompt.combined())
      val command = TerminalCommandSanitizer.sanitize(case.reference)
      val syntax = TerminalShellSyntaxChecker.check(case.shell, command)
      TerminalCommandQualityEvaluator.score(
        case,
        TerminalEvalObservation(
          candidate = command,
          durationMillis = (System.nanoTime() - caseStartedAt) / 1_000_000,
          syntaxOutcome = syntax,
        ),
      )
    }
    val report = TerminalEvalRunReport(
      metadata = TerminalEvalReportWriter.metadata(
        datasetVersion = dataset.version,
        provider = "deterministic-fixture",
        model = "positive-reference",
        reasoningEffort = "not-applicable",
        profile = "all-200",
        randomSeed = DEFAULT_SEED,
        warmRepetitions = 0,
        advisory = false,
        now = startedAt,
      ),
      completedAt = Instant.now().toString(),
      results = results,
    )
    val paths = TerminalEvalReportWriter.write(
      Path.of(System.getProperty("terminal.eval.outputDir", "build/reports/terminal-deterministic")),
      report,
    )
    val failures = results.filterNot(TerminalEvalResult::passed)
    println("Deterministic terminal corpus: ${results.size - failures.size}/${results.size} passed")
    println("Reports: ${paths.json}, ${paths.jsonLines}, ${paths.markdown}, ${paths.junitXml}")
    check(failures.isEmpty()) {
      "Deterministic terminal evaluation failed: " +
        failures.joinToString { "${it.id}(${it.failureRules.joinToString()})" }
    }
  }

  internal fun prompt(case: TerminalEvalCase) = TerminalCommandPromptBuilder.build(
    TerminalPromptContext(
      description = case.description,
      shell = case.shell,
      workingDirectory = workingDirectory(case.platform),
      projectName = case.projectName,
      projectMarkers = case.projectMarkers,
      platform = case.platform,
    ),
  )

  internal fun requireOracleAbsent(case: TerminalEvalCase, prompt: String) {
    val reference = case.reference.trim()
    val requestAlreadyContainsReference = case.description.contains(reference, ignoreCase = true)
    if (reference.length >= 8 && !requestAlreadyContainsReference) {
      require(reference !in prompt) { "${case.id}: reference command leaked into provider prompt" }
    }
  }

  private fun workingDirectory(platform: String): String = when (platform) {
    "windows" -> "C:\\workspace\\sample-project"
    else -> "/workspace/sample-project"
  }

  private const val DEFAULT_SEED = 20260722
}
