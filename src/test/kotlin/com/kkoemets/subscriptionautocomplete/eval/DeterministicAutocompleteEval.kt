package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionLimits
import com.kkoemets.subscriptionautocomplete.completion.CompletionPromptBuilder
import com.kkoemets.subscriptionautocomplete.completion.CompletionSanitizer
import java.nio.file.Path
import java.time.Instant

object DeterministicAutocompleteEval {
  @JvmStatic
  fun main(args: Array<String>) {
    val startedAt = Instant.now()
    val dataset = EvalDatasetLoader.load()
    val results = dataset.cases.map { case ->
      val caseStartedAt = System.nanoTime()
      val observation = observe(case)
      val durationMillis = (System.nanoTime() - caseStartedAt) / 1_000_000
      EvalMetrics.score(case, observation.copy(durationMillis = durationMillis))
    }
    val report = EvalRunReport(
      metadata = EvalEnvironment.capture(
        datasetVersion = dataset.version,
        provider = "deterministic-fixture",
        model = "exact-or-policy-oracle",
        completionMode = CompletionMode.AUTOMATIC.name,
        profile = "all-99",
        contextTokenBudget = 800,
        maxOutputTokens = 512,
        randomSeed = 20260718,
        advisory = false,
        now = startedAt,
      ),
      completedAt = Instant.now().toString(),
      results = results,
    )
    val paths = EvalReportWriter.write(
      Path.of(System.getProperty("eval.outputDir", "build/reports/autocomplete-deterministic")),
      report,
    )
    val failures = results.filterNot(EvalCaseResult::passed)
    println("Deterministic corpus: ${results.size - failures.size}/${results.size} passed")
    println("Reports: ${paths.json}, ${paths.jsonLines}, ${paths.markdown}, ${paths.junitXml}")
    check(failures.isEmpty()) {
      "Deterministic evaluation failed: ${failures.joinToString { it.id }}"
    }
  }

  private fun observe(case: EvalCase): EvalObservation {
    if (case.kind == EvalTaskKind.NEGATIVE) {
      return when (case.oracle.negativeExpectation) {
        NegativeExpectation.NO_BACKEND_CALL -> EvalObservation("", 0, false, 0)
        NegativeExpectation.NO_RENDERED_SUGGESTION -> EvalObservation("", 1, false, 0)
        null -> error("Negative case ${case.id} has no expectation")
      }
    }
    val context = case.input.toCompletionContext()
    val prompt = CompletionPromptBuilder.build(context, CompletionMode.AUTOMATIC)
    val outputTokens = CompletionLimits.outputTokens(512, CompletionMode.AUTOMATIC, prompt.intent)
    EvalLeakGuard.requireOracleAbsent(case, prompt.combined())
    val completion = CompletionSanitizer.sanitize(
      case.oracle.reference,
      context.prefix,
      context.suffix,
      maxTokens = outputTokens,
      languageId = context.languageId,
      intent = prompt.intent,
    )
    return EvalObservation(
      candidate = completion,
      backendCalls = 1,
      rendered = completion.isNotBlank(),
      durationMillis = 0,
    )
  }
}
