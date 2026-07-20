package com.kkoemets.subscriptionautocomplete.provider

import com.kkoemets.subscriptionautocomplete.completion.ResolvedCompletionEngine
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.openapi.application.ApplicationManager

object BackendRegistry {
  private val backendList: List<CompletionBackend> = listOf(
    ClaudeBackend(),
    CodexBackend(),
  )
  private val backends = backendList.associateBy(CompletionBackend::provider)

  fun forProvider(provider: ProviderKind): CompletionBackend {
    runCatching {
      ApplicationManager.getApplication().getService(ProviderSessionLifecycle::class.java)
    }
    return requireNotNull(backends[provider])
  }

  fun forEngine(engine: ResolvedCompletionEngine): CompletionBackend? = engine.provider?.let(::forProvider)

  fun shutdown() {
    backendList.filterIsInstance<AutoCloseable>().forEach { runCatching { it.close() } }
  }
}
