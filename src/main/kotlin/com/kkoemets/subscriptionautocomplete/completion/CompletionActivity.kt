package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.util.messages.Topic

enum class CompletionActivityPhase {
  IDLE,
  PREPARING,
  REQUESTING,
  CHECKING,
  READY,
  NO_RESULT,
  FAILED,
  ;

  val isActive: Boolean
    get() = this == PREPARING || this == REQUESTING || this == CHECKING
}

data class CompletionStageTimings(
  val contextMillis: Long? = null,
  val providerMillis: Long? = null,
  val sanitizeMillis: Long? = null,
  val validationMillis: Long? = null,
  val totalMillis: Long? = null,
)

data class CompletionActivitySnapshot(
  val requestId: Long,
  val phase: CompletionActivityPhase,
  val elapsedMillis: Long,
  val source: CompletionCandidateSource = CompletionCandidateSource.PROVIDER,
  val provider: ProviderKind? = null,
  val terminalReason: CompletionTerminalReason = CompletionTerminalReason.NONE,
  val timings: CompletionStageTimings = CompletionStageTimings(),
  val startedAtMillis: Long = 0,
  val surface: CompletionSurface = CompletionSurface.EDITOR,
) {
  fun elapsedAt(nowMillis: Long): Long {
    if (!phase.isActive || startedAtMillis <= 0) return elapsedMillis
    return maxOf(elapsedMillis, nowMillis - startedAtMillis).coerceAtLeast(0)
  }
}

fun interface CompletionActivityListener {
  fun activityChanged(snapshot: CompletionActivitySnapshot)
}

@JvmField
val COMPLETION_ACTIVITY_TOPIC: Topic<CompletionActivityListener> = Topic.create(
  "Subscription autocomplete activity changed",
  CompletionActivityListener::class.java,
)

internal class CompletionActivityState(
  private val clockMillis: () -> Long = System::currentTimeMillis,
) {
  private val lock = Any()
  private var current = ActivityProgress(
    CompletionActivitySnapshot(0, CompletionActivityPhase.IDLE, 0),
    emptyMap(),
    null,
  )

  fun snapshot(): CompletionActivitySnapshot = synchronized(lock) { current.snapshot }

  fun accept(event: CompletionPipelineEvent): CompletionActivitySnapshot? = synchronized(lock) {
    if (event.requestId < current.snapshot.requestId) return@synchronized null
    val newRequest = event.requestId > current.snapshot.requestId
    if (!newRequest && current.snapshot.terminalReason != CompletionTerminalReason.NONE) {
      return@synchronized null
    }
    if (!newRequest && event.stage.order < (current.stage?.order ?: -1)) {
      return@synchronized null
    }

    val elapsedMillis = if (newRequest) {
      event.elapsedMillis.coerceAtLeast(0)
    } else {
      maxOf(current.snapshot.elapsedMillis, event.elapsedMillis).coerceAtLeast(0)
    }
    val stageElapsed = (if (newRequest) emptyMap() else current.stageElapsed) +
      (event.stage to elapsedMillis)
    val terminalReason = event.terminalReason ?: event.stage.defaultTerminalReason()
    val next = CompletionActivitySnapshot(
      requestId = event.requestId,
      phase = event.stage.activityPhase(),
      elapsedMillis = elapsedMillis,
      source = event.source,
      provider = event.provider ?: current.snapshot.provider.takeUnless { newRequest },
      terminalReason = terminalReason,
      timings = stageElapsed.toTimings(elapsedMillis),
      startedAtMillis = if (newRequest) {
        clockMillis() - elapsedMillis
      } else {
        current.snapshot.startedAtMillis
      },
      surface = event.surface,
    )
    if (next == current.snapshot && event.stage == current.stage) return@synchronized null
    current = ActivityProgress(next, stageElapsed, event.stage)
    next
  }

  fun reset(requestId: Long): CompletionActivitySnapshot? = synchronized(lock) {
    val next = CompletionActivitySnapshot(requestId, CompletionActivityPhase.IDLE, 0)
    if (next == current.snapshot) return@synchronized null
    current = ActivityProgress(next, emptyMap(), null)
    next
  }

  fun resetIfCurrent(
    requestId: Long,
    phase: CompletionActivityPhase,
  ): CompletionActivitySnapshot? = synchronized(lock) {
    if (current.snapshot.requestId != requestId || current.snapshot.phase != phase) return@synchronized null
    val next = current.snapshot.copy(phase = CompletionActivityPhase.IDLE, elapsedMillis = 0)
    current = current.copy(snapshot = next)
    next
  }
}

private data class ActivityProgress(
  val snapshot: CompletionActivitySnapshot,
  val stageElapsed: Map<CompletionPipelineStage, Long>,
  val stage: CompletionPipelineStage?,
)

private fun Map<CompletionPipelineStage, Long>.toTimings(totalMillis: Long): CompletionStageTimings =
  CompletionStageTimings(
    contextMillis = this[CompletionPipelineStage.CONTEXT_READY]
      ?.minus(this[CompletionPipelineStage.TRIGGERED] ?: 0)
      ?.coerceAtLeast(0),
    providerMillis = durationBetween(
      CompletionPipelineStage.BACKEND_STARTED,
      CompletionPipelineStage.BACKEND_FINISHED,
    ),
    sanitizeMillis = durationBetween(
      CompletionPipelineStage.BACKEND_FINISHED,
      CompletionPipelineStage.SANITIZED,
    ),
    validationMillis = durationBetween(
      CompletionPipelineStage.SANITIZED,
      CompletionPipelineStage.VALIDATED,
    ),
    totalMillis = totalMillis,
  )

private fun Map<CompletionPipelineStage, Long>.durationBetween(
  start: CompletionPipelineStage,
  end: CompletionPipelineStage,
): Long? {
  val startedAt = this[start] ?: return null
  val endedAt = this[end] ?: return null
  return (endedAt - startedAt).coerceAtLeast(0)
}

private val CompletionPipelineStage.order: Int
  get() = when (this) {
    CompletionPipelineStage.TRIGGERED -> 0
    CompletionPipelineStage.CONTEXT_READY -> 1
    CompletionPipelineStage.BACKEND_STARTED -> 2
    CompletionPipelineStage.BACKEND_FINISHED -> 3
    CompletionPipelineStage.SANITIZED -> 4
    CompletionPipelineStage.VALIDATED -> 5
    CompletionPipelineStage.STALE_REJECTED,
    CompletionPipelineStage.RENDER_READY,
    CompletionPipelineStage.NO_RESULT,
    CompletionPipelineStage.CANCELLED,
    CompletionPipelineStage.FAILED,
    -> 6
  }

private fun CompletionPipelineStage.defaultTerminalReason(): CompletionTerminalReason = when (this) {
  CompletionPipelineStage.STALE_REJECTED -> CompletionTerminalReason.STALE
  CompletionPipelineStage.RENDER_READY -> CompletionTerminalReason.READY
  CompletionPipelineStage.NO_RESULT -> CompletionTerminalReason.NO_RESULT
  CompletionPipelineStage.CANCELLED -> CompletionTerminalReason.SUPERSEDED
  CompletionPipelineStage.FAILED -> CompletionTerminalReason.PROVIDER_FAILURE
  else -> CompletionTerminalReason.NONE
}

private fun CompletionPipelineStage.activityPhase(): CompletionActivityPhase = when (this) {
  CompletionPipelineStage.TRIGGERED,
  CompletionPipelineStage.CONTEXT_READY,
  -> CompletionActivityPhase.PREPARING

  CompletionPipelineStage.BACKEND_STARTED -> CompletionActivityPhase.REQUESTING
  CompletionPipelineStage.BACKEND_FINISHED,
  CompletionPipelineStage.SANITIZED,
  CompletionPipelineStage.VALIDATED,
  -> CompletionActivityPhase.CHECKING

  CompletionPipelineStage.RENDER_READY -> CompletionActivityPhase.READY
  CompletionPipelineStage.NO_RESULT -> CompletionActivityPhase.NO_RESULT
  CompletionPipelineStage.FAILED -> CompletionActivityPhase.FAILED
  CompletionPipelineStage.STALE_REJECTED,
  CompletionPipelineStage.CANCELLED,
  -> CompletionActivityPhase.IDLE
}
