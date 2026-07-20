package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutomaticCompletionEngine
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

abstract class SubscriptionInlineCompletionTestCase : BasePlatformTestCase() {
  private lateinit var originalSettings: AutocompleteSettings.SettingsState

  override fun runInDispatchThread(): Boolean = false

  override fun setUp() {
    super.setUp()
    val settings = AutocompleteSettings.getInstance()
    originalSettings = settings.snapshot()
    settings.update { state ->
      state.enabled = true
      state.provider = ProviderKind.CODEX.name
      state.automaticEngine = AutomaticCompletionEngine.SELECTED_SUBSCRIPTION.name
      state.debounceMs = 100
      state.timeoutSeconds = 5
      state.contextTokenBudget = 800
      state.maxOutputTokens = 64
      state.allowCrossFileForSubscription = false
      state.allowCrossFileForBundledFim = false
    }
  }

  override fun tearDown() {
    try {
      AutocompleteSettings.getInstance().loadState(originalSettings)
    } finally {
      super.tearDown()
    }
  }

  internal fun installBackend(
    backend: CompletionBackend,
    observer: CompletionPipelineObserver = CompletionPipelineObserver.NONE,
  ) {
    val provider = SubscriptionCompletionProvider(
      engineResolver = CompletionEngineResolver(),
      backendLookup = { engine -> backend.takeIf { engine.provider == backend.provider } },
      observer = observer,
    )
    ExtensionTestUtil.maskExtensions(
      InlineCompletionProvider.EP_NAME,
      listOf(provider),
      testRootDisposable,
    )
  }

  internal class FixedBackend(private val completion: String) : CompletionBackend {
    override val provider: ProviderKind = ProviderKind.CODEX
    var calls = 0
      private set

    override suspend fun complete(
      prompt: CompletionPrompt,
      settings: AutocompleteSettings.SettingsState,
    ): BackendResult {
      calls += 1
      return BackendResult.Success(completion, settings.codexModel, "test fake")
    }
  }
}
