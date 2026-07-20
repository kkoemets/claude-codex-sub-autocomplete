package com.kkoemets.subscriptionautocomplete.context

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class EditorContextTracker(
  private val project: Project,
  coroutineScope: CoroutineScope,
) : Disposable {
  private val filePolicy = ContextFilePolicy(project)
  private val lock = Any()
  private val recentEdits = ArrayDeque<RecentEditRecord>()
  private val openTabs = ArrayDeque<OpenTabRecord>()
  private var revision = 0L
  private var listenerDisposable: Disposable? = null

  init {
    ApplicationManager.getApplication().messageBus.connect(this).subscribe(
      AutocompleteSettings.SETTINGS_CHANGED_TOPIC,
      AutocompleteSettingsListener { reconcile() },
    )
    coroutineScope.launch {
      while (isActive) {
        delay(EXPIRY_SWEEP_MILLIS)
        expire(System.currentTimeMillis())
      }
    }
  }

  fun reconcile() {
    val state = AutocompleteSettings.getInstance().snapshot()
    val shouldTrack = state.recentEditContextEnabled || state.openTabContextEnabled
    synchronized(lock) {
      if (shouldTrack && listenerDisposable == null) connectLocked()
      if (!shouldTrack && listenerDisposable != null) disconnectLocked()
    }
  }

  fun snapshot(): EditorContextSnapshot = synchronized(lock) {
    expireLocked(System.currentTimeMillis())
    EditorContextSnapshot(recentEdits.toList(), openTabs.toList(), revision)
  }

  private fun connectLocked() {
    val disposable = Disposer.newDisposable("subscription autocomplete editor context")
    listenerDisposable = disposable
    EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        recordEdit(event)
      }
    }, disposable)
    project.messageBus.connect(disposable).subscribe(
      FileEditorManagerListener.FILE_EDITOR_MANAGER,
      object : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = recordOpenTab(file)

        override fun fileClosed(source: FileEditorManager, file: VirtualFile) = removeOpenTab(file)

        override fun selectionChanged(event: FileEditorManagerEvent) {
          event.newFile?.let(::recordOpenTab)
        }
      },
    )
    FileEditorManager.getInstance(project).openFiles.takeLast(MAX_OPEN_TABS).forEach(::recordOpenTab)
  }

  private fun disconnectLocked() {
    listenerDisposable?.let(Disposer::dispose)
    listenerDisposable = null
    recentEdits.clear()
    openTabs.clear()
    revision += 1
  }

  private fun recordEdit(event: DocumentEvent) {
    if (event.newLength > MAX_EDIT_CHARACTERS || event.oldLength > MAX_EDIT_CHARACTERS) return
    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
    if (!quickEligible(file) || !eligibleForProject(file)) return
    val now = System.currentTimeMillis()
    val record = RecentEditRecord(file, event.offset, event.document.modificationStamp, now)
    synchronized(lock) {
      val matching = recentEdits.firstOrNull { existing ->
        existing.file == file && now - existing.editedAtMillis <= COALESCE_MILLIS &&
          kotlin.math.abs(existing.offset - event.offset) <= COALESCE_OFFSET_DISTANCE
      }
      if (matching != null) recentEdits.remove(matching)
      recentEdits.addFirst(record)
      while (recentEdits.count { it.file == file } > MAX_RECENT_PER_FILE) {
        val oldest = recentEdits.lastOrNull { it.file == file } ?: break
        recentEdits.remove(oldest)
      }
      while (recentEdits.size > MAX_RECENT_EDITS) recentEdits.removeLast()
      revision += 1
    }
  }

  private fun recordOpenTab(file: VirtualFile) {
    if (!quickEligible(file)) return
    synchronized(lock) {
      openTabs.removeIf { it.file == file }
      openTabs.addFirst(OpenTabRecord(file, System.currentTimeMillis()))
      while (openTabs.size > MAX_OPEN_TABS) openTabs.removeLast()
      revision += 1
    }
  }

  private fun removeOpenTab(file: VirtualFile) {
    synchronized(lock) {
      if (openTabs.removeIf { it.file == file }) revision += 1
    }
  }

  private fun quickEligible(file: VirtualFile): Boolean {
    if (!file.isValid || file.isDirectory || file.fileType.isBinary) return false
    val name = file.name.lowercase()
    return !name.startsWith(".env") && !SENSITIVE_NAME_PARTS.any(name::contains)
  }

  private fun eligibleForProject(file: VirtualFile): Boolean {
    if (!ApplicationManager.getApplication().isReadAccessAllowed) return false
    return runCatching { filePolicy.isEligible(file) }.getOrDefault(false)
  }

  private fun expire(now: Long) = synchronized(lock) {
    expireLocked(now)
  }

  private fun expireLocked(now: Long) {
    if (recentEdits.removeIf { now - it.editedAtMillis > RECENT_TTL_MILLIS }) revision += 1
    if (openTabs.removeIf { !it.file.isValid }) revision += 1
  }

  override fun dispose() {
    synchronized(lock) {
      disconnectLocked()
    }
  }

  companion object {
    private const val MAX_RECENT_EDITS = 32
    private const val MAX_RECENT_PER_FILE = 4
    private const val MAX_OPEN_TABS = 12
    private const val MAX_EDIT_CHARACTERS = 8 * 1024
    private const val COALESCE_MILLIS = 2_000L
    private const val COALESCE_OFFSET_DISTANCE = 600
    private const val RECENT_TTL_MILLIS = 10 * 60 * 1_000L
    private const val EXPIRY_SWEEP_MILLIS = 60_000L
    private val SENSITIVE_NAME_PARTS = setOf("credential", "secret", "private-key", "private_key")
    fun getInstance(project: Project): EditorContextTracker = project.getService(EditorContextTracker::class.java)
  }
}
