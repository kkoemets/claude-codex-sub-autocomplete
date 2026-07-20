package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class CompletionRuntimeState(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
) : Disposable {
  internal val suggestionCache = SuggestionCache()
  internal val pacing = CompletionPacing()
  private val requestSequence = AtomicLong()
  private val activity = CompletionActivityState()

  init {
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(
      AutocompleteSettings.SETTINGS_CHANGED_TOPIC,
      AutocompleteSettingsListener { clear() },
    )
  }

  fun nextRequestId(): Long = requestSequence.incrementAndGet()

  fun activitySnapshot(): CompletionActivitySnapshot = activity.snapshot()

  fun observe(event: CompletionPipelineEvent) {
    val snapshot = activity.accept(event) ?: return
    publishActivity(snapshot)
    val visibleMillis = when (snapshot.phase) {
      CompletionActivityPhase.READY -> READY_VISIBLE_MILLIS
      CompletionActivityPhase.NO_RESULT -> NO_RESULT_VISIBLE_MILLIS
      CompletionActivityPhase.FAILED -> FAILURE_VISIBLE_MILLIS
      else -> return
    }
    coroutineScope.launch {
      delay(visibleMillis)
      activity.resetIfCurrent(snapshot.requestId, snapshot.phase)?.let(::publishActivity)
    }
  }

  fun clear() {
    suggestionCache.clear()
    pacing.clear()
    activity.reset(requestSequence.incrementAndGet())?.let(::publishActivity)
  }

  override fun dispose() {
    suggestionCache.clear()
    pacing.clear()
    activity.reset(requestSequence.incrementAndGet())
  }

  private fun publishActivity(snapshot: CompletionActivitySnapshot) {
    if (project.isDisposed) return
    project.messageBus.syncPublisher(COMPLETION_ACTIVITY_TOPIC).activityChanged(snapshot)
  }

  companion object {
    private const val READY_VISIBLE_MILLIS = 3_000L
    private const val NO_RESULT_VISIBLE_MILLIS = 2_500L
    private const val FAILURE_VISIBLE_MILLIS = 8_000L

    fun getInstance(project: Project): CompletionRuntimeState = project.getService(CompletionRuntimeState::class.java)
  }
}
