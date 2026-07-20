package com.kkoemets.subscriptionautocomplete.diagnostics

import com.kkoemets.subscriptionautocomplete.context.SecretRedactor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.messages.Topic
import java.time.Instant
import java.util.ArrayDeque

enum class DiagnosticLevel {
  INFO,
  WARNING,
  ERROR,
}

data class DiagnosticEntry(
  val timestamp: Instant,
  val level: DiagnosticLevel,
  val summary: String,
  val details: String,
)

fun interface DiagnosticsListener {
  fun diagnosticsChanged()
}

@Service(Service.Level.APP)
class DiagnosticsLog {
  private val entries = ArrayDeque<DiagnosticEntry>()
  private val coalescedAt = HashMap<String, Long>()
  private val logger = Logger.getInstance(DiagnosticsLog::class.java)

  fun info(summary: String, details: String = "") = record(DiagnosticLevel.INFO, summary, details)

  fun infoCoalesced(key: String, summary: String, details: String = "", intervalMillis: Long = 2_000) {
    val now = System.currentTimeMillis()
    val shouldRecord = synchronized(coalescedAt) {
      coalescedAt.entries.removeIf { now - it.value > COALESCED_KEY_TTL_MILLIS }
      val previous = coalescedAt[key]
      if (previous != null && now - previous < intervalMillis.coerceAtLeast(0)) {
        false
      } else {
        coalescedAt[key] = now
        true
      }
    }
    if (shouldRecord) record(DiagnosticLevel.INFO, summary, details)
  }

  fun warning(summary: String, details: String = "") = record(DiagnosticLevel.WARNING, summary, details)

  fun error(summary: String, details: String = "") = record(DiagnosticLevel.ERROR, summary, details)

  fun snapshot(): List<DiagnosticEntry> = synchronized(entries) { entries.toList() }

  fun clear() {
    synchronized(entries) { entries.clear() }
    synchronized(coalescedAt) { coalescedAt.clear() }
    publishChange()
  }

  private fun record(level: DiagnosticLevel, summary: String, details: String) {
    val safeSummary = SecretRedactor.redact(summary).take(MAX_SUMMARY_CHARS)
    val safeDetails = SecretRedactor.redact(details).take(MAX_DETAILS_CHARS)
    val entry = DiagnosticEntry(Instant.now(), level, safeSummary, safeDetails)
    synchronized(entries) {
      entries.addLast(entry)
      while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }
    val logMessage = buildString {
      append(safeSummary)
      if (safeDetails.isNotBlank()) append(" — ").append(safeDetails)
    }
    when (level) {
      DiagnosticLevel.INFO -> logger.info(logMessage)
      DiagnosticLevel.WARNING -> logger.warn(logMessage)
      DiagnosticLevel.ERROR -> logger.warn("[ERROR] $logMessage")
    }
    publishChange()
  }

  private fun publishChange() {
    ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).diagnosticsChanged()
  }

  companion object {
    val TOPIC: Topic<DiagnosticsListener> = Topic.create(
      "Subscription autocomplete diagnostics",
      DiagnosticsListener::class.java,
    )

    private const val MAX_ENTRIES = 500
    private const val MAX_SUMMARY_CHARS = 500
    private const val MAX_DETAILS_CHARS = 4_000
    private const val COALESCED_KEY_TTL_MILLIS = 60_000L

    fun getInstance(): DiagnosticsLog =
      ApplicationManager.getApplication().getService(DiagnosticsLog::class.java)
  }
}
