package com.kkoemets.subscriptionautocomplete.status

import com.kkoemets.subscriptionautocomplete.completion.COMPLETION_ACTIVITY_TOPIC
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivityListener
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivityPhase
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivitySnapshot
import com.kkoemets.subscriptionautocomplete.completion.CompletionCandidateSource
import com.kkoemets.subscriptionautocomplete.completion.CompletionRuntimeState
import com.kkoemets.subscriptionautocomplete.completion.CompletionStageTimings
import com.kkoemets.subscriptionautocomplete.completion.CompletionTerminalReason
import com.kkoemets.subscriptionautocomplete.completion.TriggerCompletionAction
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsDialog
import com.kkoemets.subscriptionautocomplete.nextedit.NextEditProposalAction
import com.kkoemets.subscriptionautocomplete.nextedit.NextEditProposalService
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteConfigurable
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutomaticCompletionEngine
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.intellij.util.messages.MessageBusConnection
import java.awt.Component
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.Timer

class ProviderStatusWidgetFactory : StatusBarWidgetFactory {
  override fun getId(): String = ProviderStatusWidget.ID

  override fun getDisplayName(): String = "Claude/Codex Sub Autocomplete"

  override fun isAvailable(project: Project): Boolean = true

  override fun createWidget(project: Project): StatusBarWidget = ProviderStatusWidget(project)

  override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()

  override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class ProviderStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
  private var statusBar: StatusBar? = null
  private var activityConnection: MessageBusConnection? = null
  private val elapsedRefreshTimer = ProviderStatusRefreshTimer(refresh = {
    if (!project.isDisposed) statusBar?.updateWidget(ID)
  })

  override fun ID(): String = ID

  override fun install(statusBar: StatusBar) {
    this.statusBar = statusBar
    activityConnection?.dispose()
    activityConnection = project.messageBus.connect().apply {
      subscribe(
        COMPLETION_ACTIVITY_TOPIC,
        CompletionActivityListener { snapshot ->
          ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
              elapsedRefreshTimer.update(snapshot)
              this@ProviderStatusWidget.statusBar?.updateWidget(ID)
            }
          }
        },
      )
    }
    elapsedRefreshTimer.update(CompletionRuntimeState.getInstance(project).activitySnapshot())
  }

  override fun dispose() {
    activityConnection?.dispose()
    activityConnection = null
    elapsedRefreshTimer.dispose()
    statusBar = null
  }

  override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

  override fun getText(): String {
    val settings = AutocompleteSettings.getInstance()
    val state = settings.state
    if (NextEditProposalService.getInstance(project).isRunning()) {
      return ProviderStatusPresentation.relatedEditText(state, settings.selectedProvider())
    }
    val activity = CompletionRuntimeState.getInstance(project).activitySnapshot()
    return ProviderStatusPresentation.text(state, settings.selectedProvider(), activity)
  }

  override fun getTooltipText(): String {
    val settings = AutocompleteSettings.getInstance()
    if (NextEditProposalService.getInstance(project).isRunning()) {
      return ProviderStatusPresentation.relatedEditTooltip(settings.state, settings.selectedProvider())
    }
    val activity = CompletionRuntimeState.getInstance(project).activitySnapshot()
    return ProviderStatusPresentation.tooltip(settings.state, settings.selectedProvider(), activity)
  }

  override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
    event -> showMenu(event)
  }

  override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

  private fun showMenu(event: MouseEvent) {
    val settings = AutocompleteSettings.getInstance()
    val actions = DefaultActionGroup().apply {
      add(object : ToggleAction("Enable inline completions") {
        override fun isSelected(event: AnActionEvent): Boolean = settings.state.enabled

        override fun setSelected(event: AnActionEvent, state: Boolean) {
          settings.update { it.enabled = state }
          statusBar?.updateWidget(ID)
        }
      })
      add(object : DumbAwareAction("Trigger Completion") {
        override fun actionPerformed(event: AnActionEvent) {
          TriggerCompletionAction.trigger(project)
        }
      })
      add(object : DumbAwareAction("Propose Related Cross-File Edit…") {
        override fun update(event: AnActionEvent) {
          val proposalRunning = NextEditProposalService.getInstance(project).isRunning()
          event.presentation.isEnabled = settings.state.enabled &&
            settings.state.allowCrossFileForSubscription &&
            !proposalRunning
          event.presentation.description = when {
            proposalRunning -> "A related-edit proposal is already running"
            event.presentation.isEnabled -> "Request a read-only related-edit proposal"
            else -> "Enable subscription cross-file context in settings first"
          }
        }

        override fun actionPerformed(event: AnActionEvent) {
          NextEditProposalAction.request(project)
        }
      })
      add(Separator.getInstance())
      add(object : DumbAwareAction("Connection Tests and Diagnostics…") {
        override fun actionPerformed(event: AnActionEvent) {
          DiagnosticsDialog.open(project)
        }
      })
      add(object : DumbAwareAction("Settings…") {
        override fun actionPerformed(event: AnActionEvent) {
          ShowSettingsUtil.getInstance().showSettingsDialog(project, AutocompleteConfigurable::class.java)
        }
      })
    }
    ActionManager.getInstance()
      .createActionPopupMenu(ActionPlaces.STATUS_BAR_PLACE, actions)
      .component
      .show(event.component, event.x, event.y)
  }

  companion object {
    const val ID = "SubscriptionAutocomplete"
  }
}

internal object ProviderStatusPresentation {
  fun relatedEditText(
    settings: AutocompleteSettings.SettingsState,
    provider: ProviderKind,
  ): String = "AI ◐ related edit · ${provider.shortName()}: ${provider.model(settings).safeModelLabel()}"

  fun relatedEditTooltip(
    settings: AutocompleteSettings.SettingsState,
    provider: ProviderKind,
  ): String = "${provider.shortName()} ${provider.model(settings).safeModelLabel()} is generating a read-only " +
    "related cross-file edit proposal. No file will be changed."

  fun text(
    settings: AutocompleteSettings.SettingsState,
    provider: ProviderKind,
    activity: CompletionActivitySnapshot,
    nowMillis: Long = System.currentTimeMillis(),
  ): String {
    if (!settings.enabled) return "AI ○ · off"
    val source = sourceLabel(activity, provider)
    val elapsedMillis = activity.elapsedAt(nowMillis)
    val elapsed = formatDuration(elapsedMillis)
    val activityMarker = activityMarker(elapsedMillis)
    return when (activity.phase) {
      CompletionActivityPhase.IDLE -> idleText(settings, provider)
      CompletionActivityPhase.PREPARING -> "AI $activityMarker · gathering · $elapsed"
      CompletionActivityPhase.REQUESTING -> "AI $activityMarker · $source generating · $elapsed"
      CompletionActivityPhase.CHECKING -> "AI $activityMarker · checking · $elapsed"
      CompletionActivityPhase.READY -> "AI ✓ ready · $source"
      CompletionActivityPhase.NO_RESULT -> "AI – ${activity.terminalReason.shortLabel()} · $source"
      CompletionActivityPhase.FAILED -> "AI ! ${activity.terminalReason.shortLabel()} · $source"
    }
  }

  fun tooltip(
    settings: AutocompleteSettings.SettingsState,
    provider: ProviderKind,
    activity: CompletionActivitySnapshot,
  ): String {
    val activityProvider = activity.provider ?: provider
    val target = "${activityProvider.shortName()} ${activityProvider.model(settings).safeModelLabel()}"
    val source = sourceDescription(activity, activityProvider, settings)
    val elapsed = formatDuration(activity.elapsedAt(System.currentTimeMillis()))
    val timings = activity.timings.summarySuffix()
    val status = if (!settings.enabled) {
      "Subscription autocomplete is disabled."
    } else {
      when (activity.phase) {
        CompletionActivityPhase.IDLE -> if (settings.selectedAutomaticEngine() == AutomaticCompletionEngine.OFF) {
          "Ready to request $target with the completion hotkey.${activity.lastOutcomeSuffix()}"
        } else {
          "Ready to request automatic completions from $target.${activity.lastOutcomeSuffix()}"
        }
        CompletionActivityPhase.PREPARING -> "Gathering bounded code context for $source ($elapsed elapsed)."
        CompletionActivityPhase.REQUESTING -> "$source is generating an inline completion ($elapsed elapsed)."
        CompletionActivityPhase.CHECKING -> "Sanitizing and validating the completion from $source ($elapsed elapsed)."
        CompletionActivityPhase.READY -> "Inline suggestion from $source is ready. Press Tab to accept it.$timings"
        CompletionActivityPhase.NO_RESULT -> "Last result from $source: ${activity.terminalReason.description()}.$timings"
        CompletionActivityPhase.FAILED ->
          "Last request to $source: ${activity.terminalReason.description()}. Open diagnostics for details.$timings"
      }
    }
    return "$status Click for settings, connectivity tests, and diagnostics."
  }

  private fun idleText(settings: AutocompleteSettings.SettingsState, provider: ProviderKind): String {
    val providerText = "${provider.shortName()}: ${provider.model(settings).safeModelLabel()}"
    return if (settings.selectedAutomaticEngine() == AutomaticCompletionEngine.OFF) {
      "AI ⌨ hotkey · $providerText"
    } else {
      "AI ○ idle · $providerText"
    }
  }

  private fun activityMarker(elapsedMillis: Long): String =
    ACTIVITY_MARKERS[((elapsedMillis / ACTIVITY_FRAME_MILLIS) % ACTIVITY_MARKERS.size).toInt()]

  private fun ProviderKind.shortName(): String = when (this) {
    ProviderKind.CLAUDE -> "Claude"
    ProviderKind.CODEX -> "Codex"
  }

  private fun ProviderKind.model(settings: AutocompleteSettings.SettingsState): String = when (this) {
    ProviderKind.CLAUDE -> settings.claudeModel
    ProviderKind.CODEX -> settings.codexModel
  }

  private fun sourceLabel(activity: CompletionActivitySnapshot, fallback: ProviderKind): String =
    when (activity.source) {
      CompletionCandidateSource.PROVIDER -> (activity.provider ?: fallback).shortName()
      CompletionCandidateSource.CACHE -> "cache"
    }

  private fun sourceDescription(
    activity: CompletionActivitySnapshot,
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): String =
    when (activity.source) {
      CompletionCandidateSource.PROVIDER -> "${provider.shortName()} ${provider.model(settings).safeModelLabel()}"
      CompletionCandidateSource.CACHE -> "the local suggestion cache"
    }

  private fun CompletionActivitySnapshot.lastOutcomeSuffix(): String = when (terminalReason) {
    CompletionTerminalReason.NONE,
    CompletionTerminalReason.SUPERSEDED,
    -> ""
    else -> " Last request: ${terminalReason.description()}.${timings.summarySuffix()}"
  }

  private fun CompletionTerminalReason.shortLabel(): String = when (this) {
    CompletionTerminalReason.NONE,
    CompletionTerminalReason.NO_RESULT,
    -> "no suggestion"
    CompletionTerminalReason.READY -> "suggestion ready"
    CompletionTerminalReason.BLANK -> "blank result"
    CompletionTerminalReason.SYNTAX_REJECTED -> "syntax rejected"
    CompletionTerminalReason.STALE -> "stale result"
    CompletionTerminalReason.SUPERSEDED -> "superseded"
    CompletionTerminalReason.CANCELLED -> "cancelled"
    CompletionTerminalReason.TIMEOUT -> "timed out"
    CompletionTerminalReason.OUTPUT_LIMIT -> "output too long"
    CompletionTerminalReason.PROVIDER_FAILURE -> "request failed"
  }

  private fun CompletionTerminalReason.description(): String = when (this) {
    CompletionTerminalReason.NONE,
    CompletionTerminalReason.NO_RESULT,
    -> "no insertable suggestion"
    CompletionTerminalReason.READY -> "suggestion ready"
    CompletionTerminalReason.BLANK -> "provider returned a blank result"
    CompletionTerminalReason.SYNTAX_REJECTED -> "suggestion was rejected by syntax validation"
    CompletionTerminalReason.STALE -> "suggestion became stale before display"
    CompletionTerminalReason.SUPERSEDED -> "request was superseded by newer typing"
    CompletionTerminalReason.CANCELLED -> "request was cancelled"
    CompletionTerminalReason.TIMEOUT -> "provider timed out"
    CompletionTerminalReason.OUTPUT_LIMIT -> "provider output exceeded the local safety envelope"
    CompletionTerminalReason.PROVIDER_FAILURE -> "provider request failed"
  }

  private fun CompletionStageTimings.summarySuffix(): String {
    val parts = listOfNotNull(
      contextMillis?.let { "context ${formatDuration(it)}" },
      providerMillis?.let { "provider ${formatDuration(it)}" },
      sanitizeMillis?.let { "sanitize ${formatDuration(it)}" },
      validationMillis?.let { "validation ${formatDuration(it)}" },
      totalMillis?.let { "total ${formatDuration(it)}" },
    )
    return if (parts.isEmpty()) "" else " Timings: ${parts.joinToString(", ")}."
  }

  private fun formatDuration(millis: Long): String = if (millis < 1_000) {
    "$millis ms"
  } else {
    String.format(Locale.ROOT, "%.1f s", millis / 1_000.0)
  }

  private fun String.safeModelLabel(): String =
    takeIf { model ->
      model.isNotBlank() && model.length <= 80 &&
        !model.contains('/') && !model.contains('\\') && !model.contains('\n') && !model.contains('\r')
    } ?: "custom model"

  private const val ACTIVITY_FRAME_MILLIS = 250L
  private val ACTIVITY_MARKERS = listOf("◐", "◓", "◑", "◒")
}

internal class ProviderStatusRefreshTimer(
  delayMillis: Int = 250,
  refresh: () -> Unit,
) : Disposable {
  private val timer = Timer(delayMillis) { refresh() }.apply { isRepeats = true }
  private var disposed = false

  fun update(snapshot: CompletionActivitySnapshot) {
    if (disposed) return
    if (snapshot.phase.isActive) {
      if (!timer.isRunning) timer.start()
    } else {
      timer.stop()
    }
  }

  fun isRunning(): Boolean = timer.isRunning

  override fun dispose() {
    disposed = true
    timer.stop()
  }
}
