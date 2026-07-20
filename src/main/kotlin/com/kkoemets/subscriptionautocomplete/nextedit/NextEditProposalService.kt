package com.kkoemets.subscriptionautocomplete.nextedit

import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog
import com.kkoemets.subscriptionautocomplete.context.ContextFilePolicy
import com.kkoemets.subscriptionautocomplete.provider.BackendRegistry
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class NextEditProposalService(
  private val project: Project,
  private val coroutineScope: CoroutineScope,
) {
  private val collector = NextEditContextCollector(project)
  private val running = AtomicBoolean()
  private val requestSequence = AtomicLong()

  fun isRunning(): Boolean = running.get()

  fun request(editor: Editor) {
    val settings = AutocompleteSettings.getInstance().snapshot()
    if (!settings.enabled) return
    if (!settings.allowCrossFileForSubscription) {
      notify(
        "Enable subscription cross-file context in Claude/Codex Sub Autocomplete settings first.",
        NotificationType.WARNING,
      )
      return
    }
    if (!running.compareAndSet(false, true)) {
      notify("A related-edit proposal request is already running.", NotificationType.INFORMATION)
      return
    }
    updateStatusWidget()
    val provider = settings.selectedProvider()
    val requestId = requestSequence.incrementAndGet()
    coroutineScope.launch {
      val startedAt = System.nanoTime()
      try {
        val context = collector.collect(editor, minOf(settings.contextTokenBudget, NEXT_EDIT_CONTEXT_TOKENS))
        if (context == null || context.targets.isEmpty()) {
          diagnostics(
            requestId,
            "skipped",
            "relatedTargets=0; elapsedMs=${elapsedMillis(startedAt)}",
          )
          notify(
            "No related cached file was found. Open a related file, enable bounded recent/open context, or invoke from a resolved reference.",
            NotificationType.INFORMATION,
          )
          return@launch
        }
        val staleReason = nextEditDiscardReason(project, context, settings.settingsRevision, provider)
        if (staleReason != null) {
          diagnostics(requestId, "discarded", "category=$staleReason; elapsedMs=${elapsedMillis(startedAt)}")
          return@launch
        }
        diagnostics(
          requestId,
          "started",
          "provider=${provider.name}; relatedTargets=${context.targets.size}",
        )
        val requestSettings = settings.copy(maxOutputTokens = minOf(settings.maxOutputTokens, NEXT_EDIT_OUTPUT_TOKENS))
        val result = BackendRegistry.forProvider(provider).complete(
          NextEditPromptBuilder.build(context),
          requestSettings,
        )
        when (result) {
          is BackendResult.Failure -> {
            diagnostics(
              requestId,
              "provider-failure",
              "provider=${provider.name}; elapsedMs=${elapsedMillis(startedAt)}",
            )
            notify("The selected provider could not generate a related-edit proposal.", NotificationType.WARNING)
          }
          is BackendResult.Success -> handleSuccess(
            requestId,
            startedAt,
            context,
            settings.settingsRevision,
            provider,
            result.model,
            result.text,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics(requestId, "failed", "elapsedMs=${elapsedMillis(startedAt)}")
        notify("The related-edit proposal could not be prepared.", NotificationType.WARNING)
      } finally {
        running.set(false)
        updateStatusWidget()
      }
    }
  }

  private fun handleSuccess(
    requestId: Long,
    startedAt: Long,
    context: NextEditRequestContext,
    expectedSettingsRevision: Long,
    expectedProvider: ProviderKind,
    model: String,
    response: String,
  ) {
    val staleReason = nextEditDiscardReason(project, context, expectedSettingsRevision, expectedProvider)
    if (staleReason != null) {
      diagnostics(requestId, "discarded", "category=$staleReason; elapsedMs=${elapsedMillis(startedAt)}")
      return
    }
    when (val parsed = NextEditProposalParser.parse(response, context.targets)) {
      is NextEditParseResult.Rejected -> {
        diagnostics(
          requestId,
          "rejected",
          "category=${parsed.reason.name}; elapsedMs=${elapsedMillis(startedAt)}",
        )
        notify("The provider returned an unsafe or unanchored proposal.", NotificationType.WARNING)
      }
      is NextEditParseResult.Success -> {
        val proposal = parsed.proposal
        diagnostics(
          requestId,
          "ready",
          "edits=${proposal.edits.size}; elapsedMs=${elapsedMillis(startedAt)}",
        )
        if (proposal.edits.isEmpty()) {
          notify("No clear related edit was proposed.", NotificationType.INFORMATION)
          return
        }
        ApplicationManager.getApplication().invokeLater({
          if (project.isDisposed) return@invokeLater
          val finalStaleReason = nextEditDiscardReason(
            project,
            context,
            expectedSettingsRevision,
            expectedProvider,
            proposal,
          )
          if (finalStaleReason != null) {
            diagnostics(
              requestId,
              "discarded",
              "category=$finalStaleReason; elapsedMs=${elapsedMillis(startedAt)}",
            )
            notify("The related-edit proposal was discarded because its context changed.", NotificationType.INFORMATION)
            return@invokeLater
          }
          NextEditProposalDialog(
            project,
            proposal,
            providerName = when (expectedProvider) {
              ProviderKind.CLAUDE -> "Claude"
              ProviderKind.CODEX -> "Codex"
            },
            modelName = model,
          ).show()
        }, ModalityState.any())
      }
    }
  }

  private fun diagnostics(requestId: Long, outcome: String, metadata: String) {
    DiagnosticsLog.getInstance().info(
      "Related-edit proposal #$requestId $outcome",
      metadata,
    )
  }

  private fun notify(message: String, type: NotificationType) {
    if (project.isDisposed) return
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Subscription Autocomplete")
      .createNotification(message, type)
      .notify(project)
  }

  private fun updateStatusWidget() {
    ApplicationManager.getApplication().invokeLater {
      if (!project.isDisposed) {
        WindowManager.getInstance().getStatusBar(project)?.updateWidget("SubscriptionAutocomplete")
      }
    }
  }

  private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

  companion object {
    private const val NEXT_EDIT_CONTEXT_TOKENS = 1_200
    private const val NEXT_EDIT_OUTPUT_TOKENS = 512
    fun getInstance(project: Project): NextEditProposalService =
      project.getService(NextEditProposalService::class.java)
  }
}

internal fun nextEditDiscardReason(
  project: Project,
  context: NextEditRequestContext,
  expectedSettingsRevision: Long,
  expectedProvider: ProviderKind,
  proposal: NextEditProposal? = null,
): String? {
  if (project.isDisposed) return "project-disposed"
  val currentSettings = AutocompleteSettings.getInstance().snapshot()
  if (
    !currentSettings.enabled ||
    !currentSettings.allowCrossFileForSubscription ||
    currentSettings.settingsRevision != expectedSettingsRevision ||
    currentSettings.selectedProvider() != expectedProvider
  ) {
    return "settings-changed"
  }
  val activeEditor = context.activeEditor ?: return "active-file-changed"
  if (!selectedEditorIsCurrent(project, activeEditor)) return "active-file-changed"
  return ApplicationManager.getApplication().runReadAction<String?> {
    val activeFile = context.activeFile
    val activeIdentity = context.activeFileIdentity
    if (
      activeEditor.isDisposed ||
      activeFile == null || activeIdentity == null ||
      activeEditor.caretModel.offset != context.activeCaretOffset ||
      FileDocumentManager.getInstance().getFile(activeEditor.document) != activeFile ||
      !documentIsCurrent(project, activeFile, activeIdentity, context.activeModificationStamp)
    ) {
      return@runReadAction "active-file-changed"
    }
    if (context.targets.any { target ->
        val file = target.file ?: return@any true
        val identity = target.fileIdentity ?: return@any true
        !documentIsCurrent(project, file, identity, target.modificationStamp)
      }
    ) {
      return@runReadAction "target-file-changed"
    }
    if (proposal != null && proposal.edits.any { edit ->
        val file = edit.targetFile ?: return@any true
        val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@any true
        document.modificationStamp != edit.targetModificationStamp || occurrences(document.text, edit.before) != 1
      }
    ) {
      return@runReadAction "proposal-anchor-changed"
    }
    null
  }
}

private fun selectedEditorIsCurrent(project: Project, expected: Editor): Boolean {
  val application = ApplicationManager.getApplication()
  if (application.isDispatchThread) {
    return !project.isDisposed && FileEditorManager.getInstance(project).selectedTextEditor === expected
  }
  var current = false
  application.invokeAndWait {
    current = !project.isDisposed && FileEditorManager.getInstance(project).selectedTextEditor === expected
  }
  return current
}

private fun documentIsCurrent(
  project: Project,
  file: VirtualFile,
  expectedIdentity: NextEditFileIdentity,
  expectedStamp: Long,
): Boolean {
  if (!file.isValid) return false
  if (captureFileIdentity(file) != expectedIdentity) return false
  if (!ContextFilePolicy(project).isEligible(file)) return false
  val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return false
  if (FileDocumentManager.getInstance().getFile(document) != file) return false
  return document.modificationStamp == expectedStamp
}

private fun occurrences(text: String, value: String): Int {
  var count = 0
  var offset = text.indexOf(value)
  while (offset >= 0) {
    count += 1
    if (count > 1) return count
    offset = text.indexOf(value, offset + value.length)
  }
  return count
}
