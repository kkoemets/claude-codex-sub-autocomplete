package com.kkoemets.subscriptionautocomplete.diagnostics

import com.kkoemets.subscriptionautocomplete.completion.CompletionActivityPhase
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivitySnapshot
import com.kkoemets.subscriptionautocomplete.completion.CompletionCandidateSource
import com.kkoemets.subscriptionautocomplete.completion.CompletionStageTimings
import com.kkoemets.subscriptionautocomplete.completion.CompletionTerminalReason
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind

internal object DiagnosticsActivityPresentation {
  fun summary(activity: CompletionActivitySnapshot, fallbackProvider: ProviderKind): String {
    if (
      activity.requestId == 0L ||
      activity.phase == CompletionActivityPhase.IDLE &&
      activity.terminalReason == CompletionTerminalReason.NONE &&
      activity.timings.totalMillis == null
    ) {
      return "Last completion: none."
    }
    val source = when (activity.source) {
      CompletionCandidateSource.PROVIDER -> "${(activity.provider ?: fallbackProvider).shortName()} provider"
      CompletionCandidateSource.CACHE -> "local cache"
    }
    val outcome = when {
      activity.terminalReason != CompletionTerminalReason.NONE -> activity.terminalReason.label()
      activity.phase == CompletionActivityPhase.PREPARING -> "preparing context"
      activity.phase == CompletionActivityPhase.REQUESTING -> "requesting"
      activity.phase == CompletionActivityPhase.CHECKING -> "checking"
      else -> "idle"
    }
    val timings = activity.timings.parts()
    return buildString {
      append("Last completion: #").append(activity.requestId)
      append(" · ").append(source)
      append(" · ").append(outcome)
      if (timings.isNotEmpty()) append(" · ").append(timings.joinToString(" · "))
    }
  }

  private fun CompletionStageTimings.parts(): List<String> = listOfNotNull(
    contextMillis?.let { "context $it ms" },
    providerMillis?.let { "provider $it ms" },
    sanitizeMillis?.let { "sanitize $it ms" },
    validationMillis?.let { "validation $it ms" },
    totalMillis?.let { "total $it ms" },
  )

  private fun CompletionTerminalReason.label(): String = when (this) {
    CompletionTerminalReason.NONE -> "idle"
    CompletionTerminalReason.READY -> "ready"
    CompletionTerminalReason.NO_RESULT -> "no result"
    CompletionTerminalReason.BLANK -> "blank"
    CompletionTerminalReason.SYNTAX_REJECTED -> "syntax rejected"
    CompletionTerminalReason.STALE -> "stale"
    CompletionTerminalReason.SUPERSEDED -> "superseded"
    CompletionTerminalReason.CANCELLED -> "cancelled"
    CompletionTerminalReason.TIMEOUT -> "timeout"
    CompletionTerminalReason.OUTPUT_LIMIT -> "output too long"
    CompletionTerminalReason.PROVIDER_FAILURE -> "provider failure"
  }

  private fun ProviderKind.shortName(): String = when (this) {
    ProviderKind.CLAUDE -> "Claude"
    ProviderKind.CODEX -> "Codex"
  }
}
