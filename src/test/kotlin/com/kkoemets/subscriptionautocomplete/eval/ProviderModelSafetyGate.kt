package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionPromptBuilder
import com.kkoemets.subscriptionautocomplete.completion.CompletionSanitizer
import com.kkoemets.subscriptionautocomplete.context.CompletionContext
import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandSanitizer

object ProviderModelSafetyGate {
  @JvmStatic
  fun main(args: Array<String>) {
    val profiles = supportedProfiles()
    val failures = buildList {
      profiles.forEach { profile ->
        UNSAFE_PROVIDER_RESPONSES.forEachIndexed { responseIndex, response ->
          val completion = CompletionSanitizer.sanitize(
            response,
            YAML_PREFIX,
            "",
            maxTokens = 512,
            languageId = "yaml",
          )
          if (completion.isNotEmpty()) {
            add("${profile.label} rendered unsafe response ${responseIndex + 1}")
          }
        }
        UNSAFE_TERMINAL_RESPONSES.forEachIndexed { responseIndex, response ->
          if (TerminalCommandSanitizer.sanitize(response).isNotEmpty()) {
            add("${profile.label} rendered unsafe terminal response ${responseIndex + 1}")
          }
        }
        VALID_TERMINAL_RESPONSES.forEachIndexed { responseIndex, response ->
          if (TerminalCommandSanitizer.sanitize(response) != response) {
            add("${profile.label} rejected valid terminal response ${responseIndex + 1}")
          }
        }
      }
    }
    val prompt = CompletionPromptBuilder.build(
      CompletionContext(
        languageId = "yaml",
        fileName = "analysis.yml",
        prefix = YAML_PREFIX,
        suffix = "",
        fragments = emptyList(),
      ),
      CompletionMode.AUTOMATIC,
    )
    check(prompt.systemPrompt.contains("If nothing should be inserted, return zero characters")) {
      "The shared provider prompt no longer requires an empty no-op response"
    }
    check(failures.isEmpty()) {
      "Provider/model prose safety gate failed: ${failures.joinToString()}"
    }
    val checks = profiles.size * (
      UNSAFE_PROVIDER_RESPONSES.size + UNSAFE_TERMINAL_RESPONSES.size + VALID_TERMINAL_RESPONSES.size
      )
    println("Provider/model editor and terminal safety gate: $checks/$checks passed across ${profiles.size} profiles")
    println(
      "Claude models: ${ProviderPolicy.claudeModels.joinToString()}; " +
        "Codex models: ${ProviderPolicy.codexFallbackChoices.joinToString()}; " +
        "Codex efforts: ${ProviderPolicy.codexReasoningEfforts.joinToString()}",
    )
  }

  internal fun supportedProfiles(): List<ModelProfile> =
    ProviderPolicy.claudeModels.map { model ->
      ModelProfile(ProviderKind.CLAUDE, model, "disabled")
    } + ProviderPolicy.codexFallbackChoices.flatMap { model ->
      ProviderPolicy.codexReasoningEfforts.map { effort ->
        ModelProfile(ProviderKind.CODEX, model, effort)
      }
    }

  internal data class ModelProfile(
    val provider: ProviderKind,
    val model: String,
    val reasoningEffort: String,
  ) {
    val label: String = "${provider.name}/$model/$reasoningEffort"
  }

  private const val YAML_PREFIX = "analysis:\n  details: complete\n  suppress: true\n"
  private val UNSAFE_PROVIDER_RESPONSES = listOf(
    "The YAML structure is already complete. The analysis block ends with the details field, and the " +
      "suppress: true is already present on the next line. No additional text needs to be inserted at the " +
      "cursor position.",
    "The file is complete and no further code needs to be added.",
    "No additional content needs to be inserted at the cursor position.",
    "Nothing needs to be inserted because the configuration is complete.",
  )
  private val UNSAFE_TERMINAL_RESPONSES = listOf(
    "Here is the command: git status --short",
    "The YAML structure is already complete as written.",
    "git status\npwd",
    "git status\u001b[31m",
  )
  private val VALID_TERMINAL_RESPONSES = listOf(
    "git status --short",
    "command -v node",
    "find . -name '*.ts' -print",
  )
}
