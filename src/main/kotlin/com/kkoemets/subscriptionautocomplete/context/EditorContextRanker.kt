package com.kkoemets.subscriptionautocomplete.context

import com.intellij.openapi.vfs.VirtualFile

object EditorContextRanker {
  fun recent(
    records: List<RecentEditRecord>,
    activeFile: VirtualFile?,
    limit: Int,
  ): List<RecentEditRecord> = records
    .asSequence()
    .filter { it.file != activeFile }
    .distinctBy { it.file.url to (it.offset / OFFSET_BUCKET) }
    .sortedWith(compareByDescending<RecentEditRecord> { score(it.file, activeFile) }
      .thenByDescending(RecentEditRecord::editedAtMillis))
    .take(limit.coerceAtLeast(0))
    .toList()

  fun openTabs(
    records: List<OpenTabRecord>,
    activeFile: VirtualFile?,
    excluded: Set<String>,
    limit: Int,
  ): List<OpenTabRecord> = records
    .asSequence()
    .filter { it.file != activeFile && it.file.url !in excluded }
    .distinctBy { it.file.url }
    .sortedWith(compareByDescending<OpenTabRecord> { score(it.file, activeFile) }
      .thenByDescending(OpenTabRecord::selectedAtMillis))
    .take(limit.coerceAtLeast(0))
    .toList()

  private fun score(file: VirtualFile, activeFile: VirtualFile?): Int {
    if (activeFile == null) return 0
    var score = 0
    if (file.extension?.lowercase() == activeFile.extension?.lowercase()) score += 4
    if (file.parent == activeFile.parent) score += 3
    if (file.parent?.parent == activeFile.parent?.parent) score += 1
    return score
  }

  private const val OFFSET_BUCKET = 384
}
