package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.settings.ProviderKind

enum class CompletionCandidateSource {
  PROVIDER,
  CACHE,
}

enum class CompletionTerminalReason {
  NONE,
  READY,
  NO_RESULT,
  BLANK,
  SYNTAX_REJECTED,
  STALE,
  SUPERSEDED,
  CANCELLED,
  TIMEOUT,
  OUTPUT_LIMIT,
  PROVIDER_FAILURE,
}

enum class CompletionPipelineStage {
  TRIGGERED,
  CONTEXT_READY,
  BACKEND_STARTED,
  BACKEND_FINISHED,
  SANITIZED,
  VALIDATED,
  STALE_REJECTED,
  RENDER_READY,
  NO_RESULT,
  CANCELLED,
  FAILED,
}

data class CompletionPipelineEvent(
  val requestId: Long,
  val stage: CompletionPipelineStage,
  val elapsedMillis: Long,
  val source: CompletionCandidateSource = CompletionCandidateSource.PROVIDER,
  val provider: ProviderKind? = null,
  val terminalReason: CompletionTerminalReason? = null,
)

fun interface CompletionPipelineObserver {
  fun onEvent(event: CompletionPipelineEvent)

  companion object {
    val NONE = CompletionPipelineObserver { }
  }
}
