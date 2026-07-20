package com.kkoemets.subscriptionautocomplete.settings

import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

enum class ProviderKind(val displayName: String) {
  CLAUDE("Claude Code subscription"),
  CODEX("ChatGPT / Codex subscription"),
  ;

  override fun toString(): String = displayName
}

enum class AutomaticCompletionEngine(val displayName: String) {
  OFF("Off — hotkey only"),
  SELECTED_SUBSCRIPTION("Selected Claude/Codex subscription"),
  BUNDLED_FIM("Bundled local FIM (not installed yet)"),
  ;

  override fun toString(): String = displayName
}

enum class SyntaxValidationMode {
  OFF,
  SHADOW,
  ENFORCE,
}

fun interface AutocompleteSettingsListener {
  fun settingsChanged(revision: Long)
}

@Service(Service.Level.APP)
@State(
  name = "SubscriptionAutocompleteSettings",
  storages = [Storage("subscriptionAutocomplete.xml")],
)
class AutocompleteSettings : PersistentStateComponent<AutocompleteSettings.SettingsState> {
  data class SettingsState(
    var settingsVersion: Int = 0,
    var enabled: Boolean = true,
    var manualOnly: Boolean = true,
    var automaticEngine: String = AutomaticCompletionEngine.OFF.name,
    var provider: String = ProviderKind.CODEX.name,
    var claudeModel: String = "haiku",
    var codexModel: String = ProviderPolicy.DEFAULT_CODEX_MODEL,
    var codexReasoningEffort: String = ProviderPolicy.DEFAULT_CODEX_EFFORT,
    var claudeExecutable: String = "",
    var codexExecutable: String = "",
    var debounceMs: Int = 750,
    var timeoutSeconds: Int = 15,
    var contextTokenBudget: Int = 1400,
    var maxOutputTokens: Int = 512,
    var allowCrossFileForSubscription: Boolean = false,
    var allowCrossFileForBundledFim: Boolean = false,
    var recentEditContextEnabled: Boolean = false,
    var openTabContextEnabled: Boolean = false,
    var syntaxValidationMode: String = SyntaxValidationMode.SHADOW.name,
    var settingsRevision: Long = 0,
  ) {
    fun selectedProvider(): ProviderKind = runCatching { ProviderKind.valueOf(provider) }
      .getOrDefault(ProviderKind.CODEX)

    fun selectedAutomaticEngine(): AutomaticCompletionEngine =
      runCatching { AutomaticCompletionEngine.valueOf(automaticEngine) }
        .getOrDefault(AutomaticCompletionEngine.OFF)

    fun selectedSyntaxValidationMode(): SyntaxValidationMode =
      runCatching { SyntaxValidationMode.valueOf(syntaxValidationMode) }
        .getOrDefault(SyntaxValidationMode.SHADOW)
  }

  private var currentState = SettingsState(settingsVersion = CURRENT_SETTINGS_VERSION)

  override fun getState(): SettingsState = currentState

  override fun loadState(state: SettingsState) {
    if (state.settingsVersion < AUTOMATIC_ENGINE_SETTINGS_VERSION) {
      state.automaticEngine = if (state.manualOnly) {
        AutomaticCompletionEngine.OFF.name
      } else {
        AutomaticCompletionEngine.SELECTED_SUBSCRIPTION.name
      }
    }
    if (
      state.settingsVersion < COMPLEX_INTENT_SETTINGS_VERSION &&
      state.maxOutputTokens == LEGACY_DEFAULT_OUTPUT_TOKENS
    ) {
      state.maxOutputTokens = DEFAULT_OUTPUT_TOKENS
    }
    if (
      state.settingsVersion < NO_REASONING_DEFAULT_SETTINGS_VERSION &&
      state.codexReasoningEffort == LEGACY_DEFAULT_CODEX_EFFORT &&
      state.codexModel in ProviderPolicy.codexNoReasoningModels
    ) {
      state.codexReasoningEffort = LEGACY_NO_REASONING_CODEX_EFFORT
    }
    if (
      state.settingsVersion < SPARK_DEFAULT_SETTINGS_VERSION &&
      state.codexModel == ProviderPolicy.LEGACY_CODEX_MODEL &&
      state.codexReasoningEffort == LEGACY_NO_REASONING_CODEX_EFFORT
    ) {
      state.codexModel = ProviderPolicy.DEFAULT_CODEX_MODEL
      state.codexReasoningEffort = ProviderPolicy.DEFAULT_CODEX_EFFORT
    }
    state.manualOnly = state.selectedAutomaticEngine() == AutomaticCompletionEngine.OFF
    state.settingsVersion = CURRENT_SETTINGS_VERSION
    state.settingsRevision = maxOf(currentState.settingsRevision, state.settingsRevision) + 1
    currentState = state
  }

  fun selectedProvider(): ProviderKind = currentState.selectedProvider()

  fun selectedAutomaticEngine(): AutomaticCompletionEngine = currentState.selectedAutomaticEngine()

  fun snapshot(): SettingsState = currentState.copy()

  fun update(mutator: (SettingsState) -> Unit) {
    val updated = currentState.copy()
    mutator(updated)
    updated.manualOnly = updated.selectedAutomaticEngine() == AutomaticCompletionEngine.OFF
    if (updated.copy(settingsRevision = currentState.settingsRevision) == currentState) return
    updated.settingsVersion = CURRENT_SETTINGS_VERSION
    updated.settingsRevision = currentState.settingsRevision + 1
    currentState = updated
    publishChanged()
  }

  private fun publishChanged() {
    runCatching {
      ApplicationManager.getApplication().messageBus
        .syncPublisher(SETTINGS_CHANGED_TOPIC)
        .settingsChanged(currentState.settingsRevision)
    }
  }

  companion object {
    private const val AUTOMATIC_ENGINE_SETTINGS_VERSION = 2
    private const val COMPLEX_INTENT_SETTINGS_VERSION = 3
    private const val NO_REASONING_DEFAULT_SETTINGS_VERSION = 4
    private const val SPARK_DEFAULT_SETTINGS_VERSION = 5
    private const val CURRENT_SETTINGS_VERSION = 5
    private const val LEGACY_DEFAULT_OUTPUT_TOKENS = 192
    private const val LEGACY_DEFAULT_CODEX_EFFORT = "low"
    private const val LEGACY_NO_REASONING_CODEX_EFFORT = "none"
    private const val DEFAULT_OUTPUT_TOKENS = 512

    @JvmField
    val SETTINGS_CHANGED_TOPIC: Topic<AutocompleteSettingsListener> = Topic.create(
      "Subscription autocomplete settings changed",
      AutocompleteSettingsListener::class.java,
    )

    fun getInstance(): AutocompleteSettings =
      ApplicationManager.getApplication().getService(AutocompleteSettings::class.java)
  }
}
