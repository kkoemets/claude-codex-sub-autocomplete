package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionLimits
import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionPromptBuilder
import com.kkoemets.subscriptionautocomplete.completion.CompletionSanitizer
import com.kkoemets.subscriptionautocomplete.provider.BackendRegistry
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlin.system.measureTimeMillis

object SubscriptionCompletionEval {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val dataset = EvalDatasetLoader.load()
    val providers = parseProviders(args.firstOrNull().orEmpty())
    val profile = System.getProperty("eval.profile", "smoke").lowercase()
    val repetitions = System.getProperty("eval.repetitions", "5").toIntOrNull()?.coerceIn(0, 20) ?: 5
    val seed = System.getProperty("eval.seed", "20260718").toIntOrNull() ?: 20260718
    val requestedCases = System.getProperty("eval.cases", "")
    val cases = selectCases(dataset, profile, requestedCases)
    val outputDirectory = Path.of(
      System.getProperty("eval.outputDir", "build/reports/subscription-evals"),
    )

    providers.forEach { provider ->
      val settings = settings(provider)
      val startedAt = Instant.now()
      val cold = cases.shuffled(Random(seed + provider.ordinal)).map { case ->
        BackendRegistry.shutdown()
        evaluate(provider, settings, case, repetition = 0, phase = "cold")
      }
      val warm = (1..repetitions).flatMap { repetition ->
        cases.shuffled(Random(seed + provider.ordinal * 100 + repetition)).map { case ->
          evaluate(provider, settings, case, repetition, phase = "warm")
        }
      }
      BackendRegistry.shutdown()
      val report = EvalRunReport(
        metadata = EvalEnvironment.capture(
          datasetVersion = dataset.version,
          provider = provider.name,
          model = selectedModel(provider, settings),
          reasoningEffort = selectedReasoningEffort(provider, settings),
          completionMode = CompletionMode.AUTOMATIC.name,
          profile = if (requestedCases.isBlank()) profile else "selected",
          contextTokenBudget = settings.contextTokenBudget,
          maxOutputTokens = settings.maxOutputTokens,
          randomSeed = seed,
          warmRepetitions = repetitions,
          advisory = true,
          now = startedAt,
        ),
        completedAt = Instant.now().toString(),
        results = cold + warm,
      )
      val paths = EvalReportWriter.write(outputDirectory, report)
      println(
        "${provider.name}: ${report.results.count(EvalCaseResult::passed)}/${report.results.size} passed; " +
          "p50=${report.aggregate.latencyP50Millis} ms; p95=${report.aggregate.latencyP95Millis} ms",
      )
      println("Reports: ${paths.json}, ${paths.jsonLines}, ${paths.markdown}, ${paths.junitXml}")
    }
  }

  private suspend fun evaluate(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
    case: EvalCase,
    repetition: Int,
    phase: String,
  ): EvalCaseResult {
    require(case.providerEligible) { "Negative cases require installed-IDE policy observation" }
    val unbudgetedPrompt = CompletionPromptBuilder.build(
      case.input.toCompletionContext(),
      CompletionMode.AUTOMATIC,
    )
    val effectiveSettings = settings.copy(
      contextTokenBudget = CompletionLimits.contextTokens(
        settings.contextTokenBudget,
        CompletionMode.AUTOMATIC,
        unbudgetedPrompt.intent,
      ),
      maxOutputTokens = CompletionLimits.outputTokens(
        settings.maxOutputTokens,
        CompletionMode.AUTOMATIC,
        unbudgetedPrompt.intent,
      ),
    )
    val context = case.input.toCompletionContext(effectiveSettings.contextTokenBudget)
    val prompt = CompletionPromptBuilder.build(context, CompletionMode.AUTOMATIC, unbudgetedPrompt.intent)
    EvalLeakGuard.requireOracleAbsent(case, prompt.combined())
    var result: BackendResult? = null
    val durationMillis = measureTimeMillis {
      result = BackendRegistry.forProvider(provider).complete(prompt, effectiveSettings)
    }
    val observation = when (val response = result) {
      is BackendResult.Success -> {
        val suggestion = CompletionSanitizer.sanitize(
          response.text,
          context.prefix,
          context.suffix,
          effectiveSettings.maxOutputTokens,
          context.languageId,
          prompt.intent,
        )
        EvalObservation(
          candidate = suggestion,
          backendCalls = 1,
          rendered = suggestion.isNotBlank(),
          durationMillis = durationMillis,
          firstTokenMillis = response.timing.firstTokenMillis,
          syntaxOutcome = EvalStructureValidator.outcome(case, suggestion),
        )
      }
      is BackendResult.Failure -> EvalObservation(
        candidate = "",
        backendCalls = 1,
        rendered = false,
        durationMillis = durationMillis,
        errorCategory = response.message,
      )
      null -> EvalObservation(
        candidate = "",
        backendCalls = 1,
        rendered = false,
        durationMillis = durationMillis,
        errorCategory = "no result",
      )
    }
    val scored = EvalMetrics.score(case, observation, phase, repetition)
    println(
      "${provider.name.padEnd(6)} ${phase.padEnd(4)} r${repetition.toString().padEnd(2)} " +
        "${case.kind.name.padEnd(22)} ${case.id.padEnd(38)} " +
        "${effectiveSettings.contextTokenBudget.toString().padStart(4)}/" +
        "${effectiveSettings.maxOutputTokens.toString().padEnd(3)} tokens  " +
        "${durationMillis.toString().padStart(6)} ms  " +
        "${scored.candidateCharacters.toString().padStart(4)} chars  " +
        if (scored.passed) "PASS" else "FAIL",
    )
    return scored
  }

  private fun selectCases(dataset: EvalDataset, profile: String, requested: String): List<EvalCase> {
    val requestedIds = requested.split(',').map(String::trim).filter(String::isNotBlank).distinct()
    if (requestedIds.isNotEmpty()) {
      val byId = (dataset.cases + supplementalCases).associateBy(EvalCase::id)
      val unknown = requestedIds.filterNot(byId::containsKey)
      require(unknown.isEmpty()) { "Unknown eval cases: ${unknown.joinToString()}" }
      return requestedIds.map(byId::getValue).onEach { case ->
        require(case.providerEligible) { "Negative case ${case.id} cannot run directly against a provider" }
      }
    }
    return when (profile) {
      "smoke" -> dataset.cases.groupBy(EvalCase::surface).values.map { surfaceCases ->
        surfaceCases.first { it.kind == EvalTaskKind.ORDINARY }
      }
      "standard" -> dataset.cases.groupBy(EvalCase::surface).values.flatMap { surfaceCases ->
        listOf(
          surfaceCases.first { it.kind == EvalTaskKind.MASKED_SPAN },
          surfaceCases.first { it.kind == EvalTaskKind.ORDINARY },
        )
      }
      "full" -> dataset.cases.filter(EvalCase::providerEligible)
      else -> error("Unknown eval profile: $profile")
    }
  }

  internal fun settings(
    provider: ProviderKind,
    property: (String, String) -> String = System::getProperty,
  ) = AutocompleteSettings.SettingsState(
    provider = provider.name,
    claudeModel = property("eval.claudeModel", ProviderPolicy.DEFAULT_CLAUDE_MODEL).trim()
      .ifEmpty { ProviderPolicy.DEFAULT_CLAUDE_MODEL },
    codexModel = property("eval.codexModel", ProviderPolicy.DEFAULT_CODEX_MODEL).trim()
      .ifEmpty { ProviderPolicy.DEFAULT_CODEX_MODEL },
    codexReasoningEffort = property(
      "eval.codexReasoningEffort",
      ProviderPolicy.DEFAULT_CODEX_EFFORT,
    ).trim().ifEmpty { ProviderPolicy.DEFAULT_CODEX_EFFORT },
    timeoutSeconds = property("eval.timeoutSeconds", "30").toIntOrNull()?.coerceIn(5, 120) ?: 30,
    contextTokenBudget = property("eval.contextTokenBudget", "1400").toIntOrNull()
      ?.coerceIn(600, 4000) ?: 1400,
    maxOutputTokens = property("eval.maxOutputTokens", "512").toIntOrNull()
      ?.coerceIn(16, 512) ?: 512,
  )

  private fun parseProviders(value: String): List<ProviderKind> = value.split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { ProviderKind.valueOf(it.uppercase()) }
    .distinct()
    .ifEmpty { listOf(ProviderKind.CODEX, ProviderKind.CLAUDE) }

  private fun selectedModel(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): String = when (provider) {
    ProviderKind.CLAUDE -> settings.claudeModel
    ProviderKind.CODEX -> settings.codexModel
  }

  private fun selectedReasoningEffort(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): String = when (provider) {
    ProviderKind.CLAUDE -> "disabled"
    ProviderKind.CODEX -> settings.codexReasoningEffort
  }

  internal val supplementalCases = listOf(
    EvalCase(
      id = "typescript-context-budget-probe",
      surface = "typescript",
      kind = EvalTaskKind.ORDINARY,
      tags = setOf("context-dependent", "multiline"),
      input = EvalInput(
        languageId = "TypeScript",
        fileName = "retry.ts",
        prefix = buildString {
          appendLine("const retryPolicy: { maxAttempts: number } = { maxAttempts: 7 }")
          (1..45).forEach { index -> appendLine("const paddingValue$index = $index") }
          appendLine("// Implement a getRetryLimit function using the existing retry configuration.")
          appendLine("// It should return the configured maximum attempt count as a number.")
        },
        suffix = "",
      ),
      oracle = EvalOracle(
        reference = "function getRetryLimit(): number {\n  return retryPolicy.maxAttempts\n}\n",
        expectedGroups = listOf(
          listOf("function\\s+getRetryLimit", "(?:const|let)\\s+getRetryLimit"),
          listOf("retryPolicy"),
          listOf("maxAttempts"),
        ),
        maxCharacters = 256,
      ),
    ),
  )
}
