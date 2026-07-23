package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.kkoemets.subscriptionautocomplete.provider.BackendRegistry
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.kkoemets.subscriptionautocomplete.terminal.TERMINAL_OUTPUT_TOKENS
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandSanitizer
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking

object SubscriptionTerminalEval {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val dataset = TerminalEvalDatasetLoader.load()
    val providers = parseProviders(args.firstOrNull().orEmpty())
    val requestedProfile = System.getProperty("terminal.eval.profile", "sample").lowercase()
    val requested = System.getProperty("terminal.eval.cases", "")
    val cases = selectCases(dataset, requestedProfile, requested)
    val profile = when {
      requested.isNotBlank() -> "ad-hoc"
      else -> requestedProfile
    }
    val repetitions = System.getProperty("terminal.eval.repetitions", "1").toIntOrNull()?.coerceIn(0, 10) ?: 1
    val seed = System.getProperty("terminal.eval.seed", "20260722").toIntOrNull() ?: 20260722
    val outputDirectory = Path.of(
      System.getProperty("terminal.eval.outputDir", "build/reports/subscription-terminal-evals"),
    )
    val reports = providers.map { provider ->
      val settings = settings(provider)
      runProfile(dataset, cases, provider, settings, profile, repetitions, seed, outputDirectory)
    }
    if (System.getProperty("terminal.eval.official", "false").toBoolean()) {
      check(providers.toSet() == setOf(ProviderKind.CLAUDE, ProviderKind.CODEX) && reports.size == 2) {
        "Official terminal live evaluation requires exactly Claude and Codex"
      }
      check(reports.all { it.metadata.official }) {
        "Official terminal live evaluation requires Claude Haiku and Codex gpt-5.4/none"
      }
    }
    reports.forEach { report -> validateIfOfficial(dataset, report) }
  }

  internal suspend fun runProfile(
    dataset: TerminalEvalDataset,
    cases: List<TerminalEvalCase>,
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
    profile: String,
    repetitions: Int,
    seed: Int,
    outputDirectory: Path,
  ): TerminalEvalRunReport {
    val startedAt = Instant.now()
    val cold = cases.shuffled(Random(seed)).map { case ->
      BackendRegistry.shutdown()
      evaluate(provider, settings, case, repetition = 0, phase = "cold")
    }
    val warm = (1..repetitions).flatMap { repetition ->
      cases.shuffled(Random(seed + repetition)).map { case ->
        evaluate(provider, settings, case, repetition, phase = "warm")
      }
    }
    BackendRegistry.shutdown()
    val caseIds = cases.map(TerminalEvalCase::id)
    val suite = TerminalLiveSuiteLoader.load(dataset)
    val official = System.getProperty("terminal.eval.official", "false").toBoolean() &&
      profile == "quality" && repetitions == 0 && caseIds == suite.caseIds &&
      isOfficialProviderProfile(provider, settings)
    val reportProfile = if (profile == "quality" && !official) "ad-hoc" else profile
    val results = cold + warm
    val report = TerminalEvalRunReport(
      metadata = TerminalEvalReportWriter.metadata(
        datasetVersion = dataset.version,
        provider = provider.name,
        model = selectedModel(provider, settings),
        reasoningEffort = selectedEffort(provider, settings),
        profile = reportProfile,
        randomSeed = seed,
        warmRepetitions = repetitions,
        advisory = !official,
        suiteId = if (official) suite.id else "ad-hoc",
        caseSetSha256 = TerminalEvalReportWriter.caseSetSha256(cases.map(::caseFingerprint)),
        uniqueCases = caseIds.distinct().size,
        attempts = results.size,
        official = official,
        now = startedAt,
      ),
      completedAt = Instant.now().toString(),
      results = results,
    )
    val paths = TerminalEvalReportWriter.write(outputDirectory, report)
    println(
      "${provider.name}/${selectedModel(provider, settings)}/${selectedEffort(provider, settings)}: " +
        "${report.aggregate.passed}/${report.aggregate.cases} passed; " +
        "p50=${report.aggregate.latencyP50Millis} ms; p95=${report.aggregate.latencyP95Millis} ms",
    )
    println("Reports: ${paths.json}, ${paths.jsonLines}, ${paths.markdown}, ${paths.junitXml}")
    return report
  }

  private suspend fun evaluate(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
    case: TerminalEvalCase,
    repetition: Int,
    phase: String,
  ): TerminalEvalResult {
    val prompt = DeterministicTerminalEval.prompt(case)
    DeterministicTerminalEval.requireOracleAbsent(case, prompt.combined())
    var backendResult: BackendResult? = null
    val durationMillis = measureTimeMillis {
      backendResult = BackendRegistry.forProvider(provider).complete(prompt, settings)
    }
    val observation = when (val response = backendResult) {
      is BackendResult.Success -> {
        val command = TerminalCommandSanitizer.sanitize(response.text, settings.maxOutputTokens)
        TerminalEvalObservation(
          candidate = command,
          durationMillis = durationMillis,
          firstTokenMillis = response.timing.firstTokenMillis,
          syntaxOutcome = TerminalShellSyntaxChecker.check(case.shell, command),
        )
      }
      is BackendResult.Failure -> TerminalEvalObservation(
        candidate = "",
        durationMillis = durationMillis,
        error = response.message,
      )
      null -> TerminalEvalObservation(candidate = "", durationMillis = durationMillis, error = "no result")
    }
    val scored = TerminalCommandQualityEvaluator.score(case, observation, phase, repetition)
    if (System.getProperty("terminal.eval.printCandidates", "false").toBoolean()) {
      println("  candidate: ${observation.candidate.ifBlank { "<blank>" }}")
    }
    println(
      "${provider.name.padEnd(6)} ${phase.padEnd(4)} r${repetition.toString().padEnd(2)} " +
        "${case.id.padEnd(48)} ${durationMillis.toString().padStart(6)} ms  " +
        if (scored.passed) "PASS" else "FAIL ${scored.failureRules.joinToString()}",
    )
    return scored
  }

  internal fun settings(
    provider: ProviderKind,
    property: (String, String) -> String = System::getProperty,
  ) = AutocompleteSettings.SettingsState(
    provider = provider.name,
    claudeModel = property("terminal.eval.claudeModel", ProviderPolicy.DEFAULT_CLAUDE_MODEL).trim()
      .ifEmpty { ProviderPolicy.DEFAULT_CLAUDE_MODEL },
    codexModel = property("terminal.eval.codexModel", ProviderPolicy.DEFAULT_CODEX_MODEL).trim()
      .ifEmpty { ProviderPolicy.DEFAULT_CODEX_MODEL },
    codexReasoningEffort = property(
      "terminal.eval.codexReasoningEffort",
      ProviderPolicy.DEFAULT_CODEX_EFFORT,
    ).trim().ifEmpty { ProviderPolicy.DEFAULT_CODEX_EFFORT },
    timeoutSeconds = property("terminal.eval.timeoutSeconds", "30").toIntOrNull()?.coerceIn(5, 120) ?: 30,
    maxOutputTokens = TERMINAL_OUTPUT_TOKENS,
  )

  internal fun selectCases(
    dataset: TerminalEvalDataset,
    profile: String,
    requested: String,
  ): List<TerminalEvalCase> {
    val requestedIds = requested.split(',').map(String::trim).filter(String::isNotBlank).distinct()
    if (requestedIds.isNotEmpty()) {
      val byId = dataset.cases.associateBy(TerminalEvalCase::id)
      val unknown = requestedIds.filterNot(byId::containsKey)
      require(unknown.isEmpty()) { "Unknown terminal eval cases: ${unknown.joinToString()}" }
      return requestedIds.map(byId::getValue)
    }
    return when (profile) {
      "sample" -> dataset.cases.groupBy(TerminalEvalCase::category).values.map(List<TerminalEvalCase>::first)
      "critical" -> CRITICAL_CASE_IDS.map { id -> dataset.cases.single { it.id == id } }
      "quality" -> TerminalLiveSuiteLoader.cases(dataset)
      "full" -> dataset.cases
      else -> error("Unknown terminal eval profile: $profile")
    }
  }

  private fun parseProviders(value: String): List<ProviderKind> = value.split(',')
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { ProviderKind.valueOf(it.uppercase()) }
    .distinct()
    .ifEmpty { listOf(ProviderKind.CODEX, ProviderKind.CLAUDE) }

  internal fun selectedModel(provider: ProviderKind, settings: AutocompleteSettings.SettingsState): String =
    if (provider == ProviderKind.CLAUDE) settings.claudeModel else settings.codexModel

  internal fun selectedEffort(provider: ProviderKind, settings: AutocompleteSettings.SettingsState): String =
    if (provider == ProviderKind.CLAUDE) "low" else settings.codexReasoningEffort

  private fun isOfficialProviderProfile(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): Boolean = when (provider) {
    ProviderKind.CLAUDE -> settings.claudeModel == "haiku"
    ProviderKind.CODEX -> settings.codexModel == "gpt-5.4" && settings.codexReasoningEffort == "none"
  }

  private fun caseFingerprint(case: TerminalEvalCase): String = listOf(
    case.id,
    case.category,
    case.request,
    case.reference,
    case.shell,
    case.platform,
    case.projectName,
    case.projectMarkers.joinToString(","),
    case.requiredGroups.joinToString(";") { it.joinToString("|") },
    case.forbiddenFragments.joinToString("|"),
    case.validator.orEmpty(),
  ).joinToString("\u001f")

  internal fun validateIfOfficial(dataset: TerminalEvalDataset, report: TerminalEvalRunReport) {
    if (!report.metadata.official) return
    val suite = TerminalLiveSuiteLoader.load(dataset)
    val aggregate = report.aggregate
    check(report.metadata.uniqueCases == 50 && report.metadata.attempts == 50) {
      "Canonical terminal quality reports must contain exactly 50 unique single-attempt cases"
    }
    check(report.results.none {
      it.candidateCharacters > 0 && it.syntaxOutcome == TerminalSyntaxOutcome.SKIPPED
    }) {
      "Canonical terminal quality reports cannot count a generated command with a skipped syntax check"
    }
    check(report.results.single { it.id == CRITICAL_SCOPE_CASE }.passed) {
      "$CRITICAL_SCOPE_CASE must pass independently of the aggregate score"
    }
    check(report.results.groupBy(TerminalEvalResult::category).values.all { results ->
      results.any(TerminalEvalResult::passed)
    }) { "Every terminal quality category must have at least one passing case" }
    println(
      "${report.metadata.provider} quality gate: ${aggregate.passed}/${aggregate.cases}; " +
        "minimum=${suite.minimumPasses}; target=${suite.targetPasses}",
    )
    check(aggregate.passed >= suite.minimumPasses) {
      "${report.metadata.provider}/${report.metadata.model} terminal quality below " +
        "90%: ${aggregate.passed}/${aggregate.cases}"
    }
  }

  private val CRITICAL_CASE_IDS = listOf(
    "files-list-all",
    "search-todos-typescript",
    "text-pretty-json",
    "git-status-short",
    "git-switch-master-child-repos",
    "gradle-build",
    "maven-test",
    "npm-ci",
    "pnpm-install-frozen",
    "python-pytest",
    "docker-build-api",
    "compose-up",
    "kubectl-get-pods",
    "terraform-plan",
    "system-port-8080-mac",
    "network-health-curl",
    "database-postgres-list",
    "archive-create-tar",
    "shell-command-v-node",
    "powershell-recent-files",
  )

  private const val CRITICAL_SCOPE_CASE = "git-switch-master-child-repos"

}
