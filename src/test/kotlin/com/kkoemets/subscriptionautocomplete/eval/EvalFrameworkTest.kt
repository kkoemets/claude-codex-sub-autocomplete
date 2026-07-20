package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.completion.CompletionPromptBuilder
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalFrameworkTest {
  @Test
  fun `live eval settings accept model and budget overrides`() {
    val values = mapOf(
      "eval.claudeModel" to "haiku",
      "eval.codexModel" to "gpt-5.5",
      "eval.codexReasoningEffort" to "none",
      "eval.timeoutSeconds" to "45",
      "eval.contextTokenBudget" to "1200",
      "eval.maxOutputTokens" to "256",
    )

    val settings = SubscriptionCompletionEval.settings(ProviderKind.CODEX) { key, default ->
      values[key] ?: default
    }

    assertEquals("haiku", settings.claudeModel)
    assertEquals("gpt-5.5", settings.codexModel)
    assertEquals("none", settings.codexReasoningEffort)
    assertEquals(45, settings.timeoutSeconds)
    assertEquals(1200, settings.contextTokenBudget)
    assertEquals(256, settings.maxOutputTokens)
  }

  @Test
  fun `context budget probe crosses the relevant declaration between 800 and 1200 tokens`() {
    val case = SubscriptionCompletionEval.supplementalCases.single()

    val leanContext = case.input.toCompletionContext(800)
    val balancedContext = case.input.toCompletionContext(1200)

    assertFalse(leanContext.prefix.contains("retryPolicy"))
    assertTrue(balancedContext.prefix.contains("retryPolicy"))
  }

  @Test
  fun `redacted structure check rejects a truncated typescript implementation`() {
    val case = EvalDatasetLoader.load().cases.first { it.id == "typescript-ordinary-local" }

    assertEquals(EvalSyntaxOutcome.PASSED, EvalStructureValidator.outcome(case, case.oracle.reference))
    assertEquals(EvalSyntaxOutcome.REJECTED, EvalStructureValidator.outcome(case, "class LRUCache {"))
  }

  @Test
  fun `loads the versioned 99 case corpus with balanced strata`() {
    val dataset = EvalDatasetLoader.load()

    assertEquals("autocomplete-corpus-v1", dataset.version)
    assertEquals(99, dataset.cases.size)
    assertEquals(11, dataset.cases.map(EvalCase::surface).distinct().size)
    assertEquals(33, dataset.taskCounts()[EvalTaskKind.MASKED_SPAN])
    assertEquals(33, dataset.taskCounts()[EvalTaskKind.ORDINARY])
    assertEquals(22, dataset.taskCounts()[EvalTaskKind.NEGATIVE])
    assertEquals(11, dataset.taskCounts()[EvalTaskKind.RECENT_CURRENT_CONTEXT])
  }

  @Test
  fun `oracle targets are absent from every provider prompt`() {
    EvalDatasetLoader.load().cases.filter(EvalCase::providerEligible).forEach { case ->
      val prompt = CompletionPromptBuilder.build(case.input.toCompletionContext(), CompletionMode.AUTOMATIC)

      EvalLeakGuard.requireOracleAbsent(case, prompt.combined())
    }
  }

  @Test
  fun `exact fake responses pass positive fixture contracts but are not provider quality`() {
    val cases = EvalDatasetLoader.load().cases.filter(EvalCase::providerEligible)
    val backend = FakeCompletionBackend.exactInsertionOnly(cases)

    assertFalse(backend.providerQualityEligible)
    cases.forEach { case ->
      val result = EvalMetrics.score(
        case,
        EvalObservation(case.oracle.reference, backendCalls = 1, rendered = true, durationMillis = 1),
      )
      assertTrue(result.passed, case.id)
    }
  }

  @Test
  fun `negative contracts distinguish request suppression from render suppression`() {
    val cases = EvalDatasetLoader.load().cases.filter { it.kind == EvalTaskKind.NEGATIVE }
    val noCall = cases.first { it.oracle.negativeExpectation == NegativeExpectation.NO_BACKEND_CALL }
    val noRender = cases.first { it.oracle.negativeExpectation == NegativeExpectation.NO_RENDERED_SUGGESTION }

    assertTrue(EvalMetrics.score(noCall, EvalObservation("", 0, false, 0)).passed)
    assertFalse(EvalMetrics.score(noCall, EvalObservation("", 1, false, 0)).passed)
    assertTrue(EvalMetrics.score(noRender, EvalObservation("discarded", 1, false, 0)).passed)
    assertFalse(EvalMetrics.score(noRender, EvalObservation("visible", 1, true, 0)).passed)
  }

  @Test
  fun `fake backend exposes deterministic barriers and cancellation`() = runBlocking {
    val barrier = FakeBackendBarrier()
    val backend = FakeCompletionBackend.responding(
      listOf(BackendResult.Success("value", model = "fixture")),
      barrier = barrier,
    )
    val job = async {
      backend.complete(CompletionPrompt("system", "input"), AutocompleteSettings.SettingsState())
    }

    barrier.started.await()
    job.cancelAndJoin()

    assertEquals(1, backend.calls)
    assertEquals(1, backend.cancellations)
    assertEquals(listOf("system\n\ninput"), backend.capturedPrompts)
  }

  @Test
  fun `reports contain metrics but no source prompts targets or suggestions`() {
    val case = EvalDatasetLoader.load().cases.first { it.id == "typescript-masked-multiline" }
    val result = EvalMetrics.score(
      case,
      EvalObservation(case.oracle.reference, 1, true, 12),
    )
    val metadata = EvalEnvironment.capture(
      datasetVersion = EvalDatasetLoader.VERSION,
      provider = "fixture",
      model = "fixture-model",
      completionMode = "AUTOMATIC",
      profile = "test",
      contextTokenBudget = 800,
      maxOutputTokens = 64,
      randomSeed = 7,
      advisory = false,
      now = Instant.parse("2026-07-18T10:00:00Z"),
    )
    val report = EvalRunReport(metadata, "2026-07-18T10:00:01Z", listOf(result))
    val directory = Files.createTempDirectory("autocomplete-eval-report")

    val paths = EvalReportWriter.write(directory, report)
    val content = listOf(paths.json, paths.jsonLines, paths.markdown, paths.junitXml)
      .joinToString("\n") { Files.readString(it) }

    assertFalse(content.contains(case.oracle.reference))
    assertFalse(content.contains(case.input.prefix))
    assertFalse(content.contains(case.input.suffix))
    assertTrue(content.contains(case.id))
  }

  @Test
  fun `baseline comparison rejects incompatible models`() {
    val case = EvalDatasetLoader.load().cases.first(EvalCase::providerEligible)
    val result = EvalMetrics.score(case, EvalObservation(case.oracle.reference, 1, true, 10))
    val baselineMetadata = EvalEnvironment.capture(
      datasetVersion = EvalDatasetLoader.VERSION,
      provider = "CLAUDE",
      model = "haiku",
      completionMode = "AUTOMATIC",
      profile = "smoke",
      contextTokenBudget = 800,
      maxOutputTokens = 64,
      randomSeed = 1,
      advisory = true,
      now = Instant.parse("2026-07-18T10:00:00Z"),
    )
    val baseline = EvalRunReport(baselineMetadata, "done", listOf(result))
    val candidate = EvalRunReport(baselineMetadata.copy(model = "sonnet"), "done", listOf(result))

    val comparison = EvalBaselineComparator.compare(baseline, candidate)

    assertFalse(comparison.compatible)
    assertEquals("incompatible-model", comparison.reason)
  }
}
