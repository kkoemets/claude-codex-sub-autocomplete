package com.kkoemets.subscriptionautocomplete.context

import com.intellij.openapi.vfs.VirtualFile

data class RecentEditRecord(
  val file: VirtualFile,
  val offset: Int,
  val modificationStamp: Long,
  val editedAtMillis: Long,
)

data class OpenTabRecord(
  val file: VirtualFile,
  val selectedAtMillis: Long,
)

data class EditorContextSnapshot(
  val recentEdits: List<RecentEditRecord>,
  val openTabs: List<OpenTabRecord>,
  val revision: Long,
)

data class CollectedEditorContext(
  val recentEdits: List<ContextFragment>,
  val openTabs: List<ContextFragment>,
)
