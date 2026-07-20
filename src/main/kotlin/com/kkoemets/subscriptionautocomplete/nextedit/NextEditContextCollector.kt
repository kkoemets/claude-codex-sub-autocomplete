package com.kkoemets.subscriptionautocomplete.nextedit

import com.kkoemets.subscriptionautocomplete.completion.CompletionDestination
import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.context.ContextEngine
import com.kkoemets.subscriptionautocomplete.context.ContextFilePolicy
import com.kkoemets.subscriptionautocomplete.context.ContextFragment
import com.kkoemets.subscriptionautocomplete.context.ContextSharingPolicy
import com.kkoemets.subscriptionautocomplete.context.SecretRedactor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

internal class NextEditContextCollector(private val project: Project) {
  private val filePolicy = ContextFilePolicy(project)

  suspend fun collect(editor: Editor, requestedBudget: Int): NextEditRequestContext? {
    val caretOffset = readAction { editor.caretModel.offset }
    val context = ContextEngine.getInstance(project).gather(
      editor = editor,
      requestedOffset = caretOffset,
      requestedBudget = requestedBudget.coerceIn(MIN_CONTEXT_TOKENS, MAX_CONTEXT_TOKENS),
      sharingPolicy = ContextSharingPolicy(CompletionDestination.SUBSCRIPTION_PROCESS, allowCrossFile = true),
      mode = CompletionMode.MANUAL,
    ) ?: return null
    val active = readAction {
      val documentSnapshot = context.documentSnapshot ?: return@readAction null
      val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@readAction null
      val file = psiFile.virtualFile ?: return@readAction null
      if (!filePolicy.isEligible(file) || editor.document.textLength > MAX_DOCUMENT_CHARS) return@readAction null
      if (
        editor.isDisposed ||
        editor.caretModel.offset != documentSnapshot.caretOffset ||
        editor.document.modificationStamp != documentSnapshot.modificationStamp ||
        file.path != documentSnapshot.fileIdentity
      ) return@readAction null
      ActiveSnapshot(
        file = file,
        identity = captureFileIdentity(file),
        modificationStamp = documentSnapshot.modificationStamp,
        caretOffset = documentSnapshot.caretOffset,
      )
    } ?: return null
    val relatedGroups = context.fragments.asSequence()
      .filter { it.origin?.crossFile == true }
      .groupBy { it.origin?.fileIdentity.orEmpty() }
      .entries
      .asSequence()
      .filter { it.key.isNotBlank() }
      .toList()
    val related = buildList {
      for (group in relatedGroups) {
        target("target-${size + 1}", group.key, group.value)?.let(::add)
        if (size == MAX_RELATED_TARGETS) break
      }
    }
    return NextEditRequestContext(
      activeFileName = context.fileName,
      languageId = context.languageId,
      prefix = context.prefix,
      suffix = context.suffix,
      activeEditor = editor,
      activeFile = active.file,
      activeFileIdentity = active.identity,
      activeModificationStamp = active.modificationStamp,
      activeCaretOffset = active.caretOffset,
      targets = related,
    )
  }

  private suspend fun target(
    id: String,
    fileIdentity: String,
    fragments: List<ContextFragment>,
  ): NextEditTarget? = readAction {
    val capturedStamp = fragments.mapNotNull { fragment -> fragment.origin?.modificationStamp }
      .distinct()
      .singleOrNull()
      ?: return@readAction null
    val file = VirtualFileManager.getInstance().findFileByUrl(fileIdentity) ?: return@readAction null
    if (!filePolicy.isEligible(file)) return@readAction null
    val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@readAction null
    if (document.modificationStamp != capturedStamp) return@readAction null
    if (document.textLength > MAX_DOCUMENT_CHARS) return@readAction null
    val excerpt = SecretRedactor.redact(
      fragments.joinToString("\n\n") { fragment -> fragment.content }.take(MAX_TARGET_EXCERPT_CHARS),
    ).trim()
    if (excerpt.isBlank()) return@readAction null
    NextEditTarget(
      id = id,
      displayName = file.name,
      languageId = PsiManager.getInstance(project).findFile(file)?.language?.id ?: file.fileType.name,
      excerpt = excerpt,
      currentText = document.text,
      modificationStamp = document.modificationStamp,
      file = file,
      fileIdentity = captureFileIdentity(file),
    )
  }

  companion object {
    private const val MIN_CONTEXT_TOKENS = 600
    private const val MAX_CONTEXT_TOKENS = 1_200
    private const val MAX_RELATED_TARGETS = 3
    private const val MAX_TARGET_EXCERPT_CHARS = 1_600
    private const val MAX_DOCUMENT_CHARS = 200_000
  }

  private data class ActiveSnapshot(
    val file: com.intellij.openapi.vfs.VirtualFile,
    val identity: NextEditFileIdentity,
    val modificationStamp: Long,
    val caretOffset: Int,
  )
}

internal fun captureFileIdentity(file: com.intellij.openapi.vfs.VirtualFile): NextEditFileIdentity = NextEditFileIdentity(
  url = file.url,
  canonicalPath = file.canonicalPath ?: file.path,
)
