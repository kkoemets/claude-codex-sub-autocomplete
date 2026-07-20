package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

class FakeCompletionBackend private constructor(
  override val provider: ProviderKind,
  responses: List<BackendResult>,
  private val barrier: FakeBackendBarrier?,
) : CompletionBackend {
  private val lock = Any()
  private val queuedResponses = ArrayDeque(responses)
  private val prompts = mutableListOf<String>()
  private val callCounter = AtomicInteger()
  private val cancellationCounter = AtomicInteger()

  val providerQualityEligible: Boolean = false
  val calls: Int get() = callCounter.get()
  val cancellations: Int get() = cancellationCounter.get()
  val capturedPrompts: List<String> get() = synchronized(lock) { prompts.toList() }

  override suspend fun complete(
    prompt: CompletionPrompt,
    settings: AutocompleteSettings.SettingsState,
  ): BackendResult {
    callCounter.incrementAndGet()
    synchronized(lock) { prompts += prompt.combined() }
    barrier?.started?.complete(Unit)
    try {
      barrier?.release?.await()
    } catch (cancelled: CancellationException) {
      cancellationCounter.incrementAndGet()
      throw cancelled
    }
    return synchronized(lock) {
      if (queuedResponses.isEmpty()) {
        BackendResult.Success("", model = "deterministic-fixture")
      } else {
        queuedResponses.removeFirst()
      }
    }
  }

  companion object {
    fun responding(
      responses: List<BackendResult>,
      provider: ProviderKind = ProviderKind.CODEX,
      barrier: FakeBackendBarrier? = null,
    ): FakeCompletionBackend = FakeCompletionBackend(provider, responses, barrier)

    fun exactInsertionOnly(
      cases: List<EvalCase>,
      provider: ProviderKind = ProviderKind.CODEX,
    ): FakeCompletionBackend = responding(
      cases.map { case ->
        require(case.providerEligible) { "Negative cases do not have exact insertion responses" }
        BackendResult.Success(case.oracle.reference, model = "deterministic-fixture", transport = "in-memory")
      },
      provider,
    )
  }
}

class FakeBackendBarrier {
  val started = CompletableDeferred<Unit>()
  val release = CompletableDeferred<Unit>()

  fun unblock() {
    release.complete(Unit)
  }
}
