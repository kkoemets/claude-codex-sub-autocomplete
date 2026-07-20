package com.kkoemets.subscriptionautocomplete.context

import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile

class EditorContextCollector(private val project: Project) {
  private val filePolicy = ContextFilePolicy(project)

  suspend fun collect(
    activeFile: VirtualFile?,
    mode: CompletionMode,
    settings: AutocompleteSettings.SettingsState,
    sharingPolicy: ContextSharingPolicy,
  ): CollectedEditorContext {
    if (!settings.recentEditContextEnabled && !settings.openTabContextEnabled) return EMPTY
    val snapshot = EditorContextTracker.getInstance(project).snapshot()
    val recentLimit = if (mode == CompletionMode.MANUAL) 3 else 2
    val openLimit = if (mode == CompletionMode.MANUAL) 2 else 1
    val recentRecords = if (settings.recentEditContextEnabled) {
      EditorContextRanker.recent(snapshot.recentEdits, activeFile, recentLimit)
    } else {
      emptyList()
    }
    val allowedRecent = recentRecords.filterEligible { record ->
      record.file == activeFile || sharingPolicy.allowCrossFile
    }
    val recent = allowedRecent.mapNotNull { record ->
      fragment(record.file, record.offset, RECENT_EDIT_TOKENS, ContextFragmentSource.RECENT_EDIT, "recent-edit")
    }
    val recentFiles = allowedRecent.mapTo(mutableSetOf()) { it.file.url }
    val openRecords = if (settings.openTabContextEnabled && sharingPolicy.allowCrossFile) {
      EditorContextRanker.openTabs(snapshot.openTabs, activeFile, recentFiles, openLimit)
        .filterEligible()
    } else {
      emptyList()
    }
    val open = openRecords.mapNotNull { record ->
      fragment(record.file, offset = 0, OPEN_TAB_TOKENS, ContextFragmentSource.OPEN_TAB, "open-tab")
    }
    return CollectedEditorContext(recent, open)
  }

  private suspend fun <T> List<T>.filterEligible(
    additional: (T) -> Boolean = { true },
  ): List<T> where T : Any = buildList {
    for (record in this@filterEligible) {
      val file = when (record) {
        is RecentEditRecord -> record.file
        is OpenTabRecord -> record.file
        else -> continue
      }
      if (additional(record) && readAction { filePolicy.isEligible(file) }) add(record)
    }
  }

  private suspend fun fragment(
    file: VirtualFile,
    offset: Int,
    maxTokens: Int,
    source: ContextFragmentSource,
    detail: String,
  ): ContextFragment? = readAction {
    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@readAction null
    if (document.textLength > MAX_DOCUMENT_CHARACTERS) return@readAction null
    val maxCharacters = maxTokens * 4
    val safeOffset = offset.coerceIn(0, document.textLength)
    val start = (safeOffset - (maxCharacters * 2) / 3).coerceAtLeast(0)
    val end = (start + maxCharacters).coerceAtMost(document.textLength)
    val adjustedStart = (end - maxCharacters).coerceAtLeast(0)
    val content = SecretRedactor.redact(document.getText(TextRange(adjustedStart, end))).trim()
    if (content.isBlank()) return@readAction null
    ContextFragment(
      label = if (source == ContextFragmentSource.RECENT_EDIT) {
        "Recent edit (${file.name})"
      } else {
        "Open tab (${file.name})"
      },
      content = content,
      priority = if (source == ContextFragmentSource.RECENT_EDIT) 70 else 52,
      maxTokens = maxTokens,
      source = source,
      sourceDetail = detail,
      origin = ContextOrigin(file.url, document.modificationStamp, crossFile = true),
    )
  }

  companion object {
    private const val RECENT_EDIT_TOKENS = 96
    private const val OPEN_TAB_TOKENS = 96
    private const val MAX_DOCUMENT_CHARACTERS = 200_000
    private val EMPTY = CollectedEditorContext(emptyList(), emptyList())
  }
}
