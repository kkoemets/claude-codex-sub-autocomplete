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
