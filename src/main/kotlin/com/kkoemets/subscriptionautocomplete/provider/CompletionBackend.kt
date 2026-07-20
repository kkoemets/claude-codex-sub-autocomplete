package com.kkoemets.subscriptionautocomplete.provider

import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.completion.CompletionEngineId
import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.context.CompletionContext
import com.kkoemets.subscriptionautocomplete.context.ContextDependencyFingerprint
import com.kkoemets.subscriptionautocomplete.context.RequestDocumentSnapshot
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind

sealed interface BackendResult {
  data class Success(
    val text: String,
    val model: String,
    val transport: String = "",
    val timing: ProviderTiming = ProviderTiming(),
  ) : BackendResult

  data class Failure(val message: String) : BackendResult
}

data class ProviderTiming(
  val firstTokenMillis: Long? = null,
  val totalMillis: Long? = null,
)

data class CompletionRequestSnapshot(
  val document: RequestDocumentSnapshot?,
  val engine: CompletionEngineId,
  val settingsRevision: Long,
  val contextDependencies: ContextDependencyFingerprint,
)

data class BackendCompletionRequest(
  val prompt: CompletionPrompt,
  val context: CompletionContext,
  val mode: CompletionMode,
  val requestSnapshot: CompletionRequestSnapshot,
)

interface CompletionBackend {
  val provider: ProviderKind

  suspend fun complete(
    prompt: CompletionPrompt,
    settings: AutocompleteSettings.SettingsState,
  ): BackendResult

  suspend fun complete(
    request: BackendCompletionRequest,
    settings: AutocompleteSettings.SettingsState,
  ): BackendResult = complete(request.prompt, settings)
}

object ProviderPolicy {
  const val DEFAULT_CLAUDE_MODEL = "haiku"
  const val CODEX_SPARK_MODEL = "gpt-5.3-codex-spark"
  const val DEFAULT_CODEX_MODEL = CODEX_SPARK_MODEL
  const val DEFAULT_CODEX_EFFORT = "low"
  const val LEGACY_CODEX_MODEL = "gpt-5.6-luna"

  val claudeModels = listOf("haiku", "sonnet", "opus")
  val codexFallbackChoices = listOf(
    DEFAULT_CODEX_MODEL,
    LEGACY_CODEX_MODEL,
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "gpt-5.5",
    "gpt-5.4",
  )
  val codexReasoningEfforts = listOf("none", "low", "medium", "high", "xhigh", "max")
  val codexNoReasoningModels = setOf(
    LEGACY_CODEX_MODEL,
    "gpt-5.6-sol",
    "gpt-5.6-terra",
    "gpt-5.5",
    "gpt-5.4",
  )
}
