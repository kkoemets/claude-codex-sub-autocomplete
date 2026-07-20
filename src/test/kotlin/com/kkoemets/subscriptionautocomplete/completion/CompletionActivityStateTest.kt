package com.kkoemets.subscriptionautocomplete.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionActivityStateTest {
  @Test
  fun `pipeline stages expose visible activity phases`() {
    val state = CompletionActivityState()

    state.accept(event(1, CompletionPipelineStage.TRIGGERED))
    assertEquals(CompletionActivityPhase.PREPARING, state.snapshot().phase)

    state.accept(event(1, CompletionPipelineStage.BACKEND_STARTED))
    assertEquals(CompletionActivityPhase.REQUESTING, state.snapshot().phase)

    state.accept(event(1, CompletionPipelineStage.SANITIZED))
    assertEquals(CompletionActivityPhase.CHECKING, state.snapshot().phase)

    state.accept(event(1, CompletionPipelineStage.RENDER_READY))
    assertEquals(CompletionActivityPhase.READY, state.snapshot().phase)
  }

  @Test
  fun `late terminal event cannot hide a newer request`() {
    val state = CompletionActivityState()
    state.accept(event(3, CompletionPipelineStage.BACKEND_STARTED))

    val change = state.accept(event(2, CompletionPipelineStage.CANCELLED))

    assertNull(change)
    assertEquals(3, state.snapshot().requestId)
    assertEquals(CompletionActivityPhase.REQUESTING, state.snapshot().phase)
  }

  @Test
  fun `same request cannot regress or change after a terminal event`() {
    val state = CompletionActivityState()
    state.accept(event(3, CompletionPipelineStage.BACKEND_STARTED))

    assertNull(state.accept(event(3, CompletionPipelineStage.CONTEXT_READY)))
    state.accept(event(3, CompletionPipelineStage.RENDER_READY))
    assertNull(state.accept(event(3, CompletionPipelineStage.BACKEND_FINISHED)))

    assertEquals(CompletionActivityPhase.READY, state.snapshot().phase)
    assertEquals(CompletionTerminalReason.READY, state.snapshot().terminalReason)
  }

  @Test
  fun `stage timings are derived without carrying source text`() {
    val state = CompletionActivityState { 10_000 }
    state.accept(event(5, CompletionPipelineStage.TRIGGERED, 0))
    state.accept(event(5, CompletionPipelineStage.CONTEXT_READY, 20))
    state.accept(event(5, CompletionPipelineStage.BACKEND_STARTED, 25))
    state.accept(event(5, CompletionPipelineStage.BACKEND_FINISHED, 125))
    state.accept(event(5, CompletionPipelineStage.SANITIZED, 130))
    state.accept(event(5, CompletionPipelineStage.VALIDATED, 140))
    state.accept(
      event(5, CompletionPipelineStage.RENDER_READY, 145).copy(
        provider = com.kkoemets.subscriptionautocomplete.settings.ProviderKind.CLAUDE,
      ),
    )

    val snapshot = state.snapshot()
    assertEquals(20, snapshot.timings.contextMillis)
    assertEquals(100, snapshot.timings.providerMillis)
    assertEquals(5, snapshot.timings.sanitizeMillis)
    assertEquals(10, snapshot.timings.validationMillis)
    assertEquals(145, snapshot.timings.totalMillis)
    assertEquals(com.kkoemets.subscriptionautocomplete.settings.ProviderKind.CLAUDE, snapshot.provider)
    assertEquals(CompletionCandidateSource.PROVIDER, snapshot.source)
  }

  @Test
  fun `active elapsed time advances from the request start`() {
    val state = CompletionActivityState { 10_000 }
    state.accept(event(6, CompletionPipelineStage.TRIGGERED, 100))

    assertEquals(500, state.snapshot().elapsedAt(10_400))
  }

  @Test
  fun `routine cancellation is recorded as a quiet supersession`() {
    val state = CompletionActivityState()

    val snapshot = state.accept(event(7, CompletionPipelineStage.CANCELLED))

    assertEquals(CompletionActivityPhase.IDLE, snapshot?.phase)
    assertEquals(CompletionTerminalReason.SUPERSEDED, snapshot?.terminalReason)
    assertTrue(state.snapshot().timings.totalMillis != null)
  }

  @Test
  fun `terminal state resets only while it is still current`() {
    val state = CompletionActivityState()
    state.accept(event(4, CompletionPipelineStage.FAILED))

    state.resetIfCurrent(4, CompletionActivityPhase.FAILED)

    assertEquals(CompletionActivityPhase.IDLE, state.snapshot().phase)
    assertNull(state.resetIfCurrent(4, CompletionActivityPhase.FAILED))
  }

  private fun event(requestId: Long, stage: CompletionPipelineStage, elapsedMillis: Long = 25) =
    CompletionPipelineEvent(requestId, stage, elapsedMillis)
}
