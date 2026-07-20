package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutomaticCompletionEngine
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind

enum class CompletionEngineId {
  CLAUDE_SUBSCRIPTION,
  CODEX_SUBSCRIPTION,
  BUNDLED_FIM,
}

data class ResolvedCompletionEngine(
  val id: CompletionEngineId,
  val provider: ProviderKind?,
  val destination: CompletionDestination,
)

enum class CompletionDestination {
  SUBSCRIPTION_PROCESS,
  BUNDLED_LOCAL_PROCESS,
}

internal open class CompletionEngineResolver {
  open fun resolve(
    mode: CompletionMode,
    settings: AutocompleteSettings.SettingsState,
  ): ResolvedCompletionEngine? = when (mode) {
    CompletionMode.MANUAL -> subscriptionEngine(settings.selectedProvider())
    CompletionMode.AUTOMATIC -> when (settings.selectedAutomaticEngine()) {
      AutomaticCompletionEngine.OFF -> null
      AutomaticCompletionEngine.SELECTED_SUBSCRIPTION -> subscriptionEngine(settings.selectedProvider())
      AutomaticCompletionEngine.BUNDLED_FIM -> ResolvedCompletionEngine(
        CompletionEngineId.BUNDLED_FIM,
        provider = null,
        destination = CompletionDestination.BUNDLED_LOCAL_PROCESS,
      )
    }
  }

  private fun subscriptionEngine(provider: ProviderKind): ResolvedCompletionEngine = ResolvedCompletionEngine(
    id = when (provider) {
      ProviderKind.CLAUDE -> CompletionEngineId.CLAUDE_SUBSCRIPTION
      ProviderKind.CODEX -> CompletionEngineId.CODEX_SUBSCRIPTION
    },
    provider = provider,
    destination = CompletionDestination.SUBSCRIPTION_PROCESS,
  )
}
