package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

object SupportedTerminalModelEval {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val dataset = TerminalEvalDatasetLoader.load()
    val profile = System.getProperty("terminal.eval.profile", "quality")
    val requested = System.getProperty("terminal.eval.cases", "")
    val cases = SubscriptionTerminalEval.selectCases(dataset, profile, requested)
    val repetitions = System.getProperty("terminal.eval.repetitions", "0").toIntOrNull()?.coerceIn(0, 10) ?: 0
    val seed = System.getProperty("terminal.eval.seed", "20260722").toIntOrNull() ?: 20260722
    val outputDirectory = Path.of(
      System.getProperty("terminal.eval.outputDir", "build/reports/terminal-supported-model-evals"),
    )
    ProviderPolicy.claudeModels.forEach { model ->
      val settings = SubscriptionTerminalEval.settings(ProviderKind.CLAUDE).copy(claudeModel = model)
      val report = SubscriptionTerminalEval.runProfile(
        dataset, cases, ProviderKind.CLAUDE, settings, profile, repetitions, seed, outputDirectory,
      )
      SubscriptionTerminalEval.validateIfOfficial(dataset, report)
    }
    ProviderPolicy.codexFallbackChoices.forEach { model ->
      ProviderPolicy.codexReasoningEfforts.forEach { effort ->
        val settings = SubscriptionTerminalEval.settings(ProviderKind.CODEX).copy(
          codexModel = model,
          codexReasoningEffort = effort,
        )
        val report = SubscriptionTerminalEval.runProfile(
          dataset, cases, ProviderKind.CODEX, settings, profile, repetitions, seed, outputDirectory,
        )
        SubscriptionTerminalEval.validateIfOfficial(dataset, report)
      }
    }
  }
}
