package com.kkoemets.subscriptionautocomplete.completion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

object FailureNotifier {
  private const val THROTTLE_MILLIS = 60_000L
  private val lastShown = ConcurrentHashMap<String, Long>()

  fun notify(project: Project, message: String) {
    val now = System.currentTimeMillis()
    val previous = lastShown.put(message, now) ?: 0L
    if (now - previous < THROTTLE_MILLIS) return
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Subscription Autocomplete")
      .createNotification(message, NotificationType.WARNING)
      .notify(project)
  }
}
