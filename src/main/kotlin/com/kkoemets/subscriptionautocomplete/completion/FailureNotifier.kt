package com.kkoemets.subscriptionautocomplete.completion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

object FailureNotifier {
  private const val THROTTLE_MILLIS = 60_000L
  private const val MAX_TRACKED_MESSAGES = 64
  private val lastShown = ConcurrentHashMap<String, Long>()

  fun notify(project: Project, message: String) {
    if (!shouldShow(message, System.currentTimeMillis())) return
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Subscription Autocomplete")
      .createNotification(message, NotificationType.WARNING)
      .notify(project)
  }

  /**
   * At most one balloon per kind of failure per minute.
   *
   * The time of the last balloon is only updated when one is shown. Updating it on every suppressed
   * call meant a failure recurring more often than once a minute was reported exactly once, ever.
   * Messages that differ only in numbers (character counts, durations) are one kind of failure.
   */
  internal fun shouldShow(message: String, now: Long): Boolean {
    val key = message.replace(DIGITS, "#").take(200)
    var show = false
    lastShown.compute(key) { _, previous ->
      if (previous != null && now - previous < THROTTLE_MILLIS) {
        previous
      } else {
        show = true
        now
      }
    }
    if (lastShown.size > MAX_TRACKED_MESSAGES) {
      lastShown.entries.removeIf { now - it.value >= THROTTLE_MILLIS }
      if (lastShown.size > MAX_TRACKED_MESSAGES) lastShown.keys.retainAll(setOf(key))
    }
    return show
  }

  internal fun resetForTests() = lastShown.clear()

  private val DIGITS = Regex("\\d+")
}
