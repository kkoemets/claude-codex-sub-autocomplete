package com.kkoemets.subscriptionautocomplete.nextedit

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile

internal class NextEditRequestContext(
  val activeFileName: String,
  val languageId: String,
  val prefix: String,
  val suffix: String,
  val activeEditor: Editor?,
  val activeFile: VirtualFile?,
  val activeFileIdentity: NextEditFileIdentity?,
  val activeModificationStamp: Long,
  val activeCaretOffset: Int,
  val targets: List<NextEditTarget>,
)

internal data class NextEditFileIdentity(
  val url: String,
  val canonicalPath: String,
)

internal class NextEditTarget(
  val id: String,
  val displayName: String,
  val languageId: String,
  val excerpt: String,
  val currentText: String,
  val modificationStamp: Long,
  val file: VirtualFile?,
  val fileIdentity: NextEditFileIdentity? = null,
)

internal data class NextEditProposal(
  val summary: String,
  val edits: List<NextEditProposalItem>,
)

internal data class NextEditProposalItem(
  val targetId: String,
  val targetName: String,
  val targetFile: VirtualFile?,
  val targetFileIdentity: NextEditFileIdentity?,
  val targetModificationStamp: Long,
  val before: String,
  val after: String,
  val rationale: String,
)

internal sealed interface NextEditParseResult {
  data class Success(val proposal: NextEditProposal) : NextEditParseResult

  data class Rejected(val reason: NextEditRejectionReason) : NextEditParseResult
}

internal enum class NextEditRejectionReason {
  EMPTY,
  TOO_LARGE,
  INVALID_JSON,
  INVALID_SCHEMA,
  TOO_MANY_EDITS,
  UNKNOWN_TARGET,
  INVALID_TEXT,
  UNANCHORED_EDIT,
  AMBIGUOUS_EDIT,
}
