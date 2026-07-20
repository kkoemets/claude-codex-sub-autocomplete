package com.kkoemets.subscriptionautocomplete.context

import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement

class SemanticContextCollector(private val project: Project) {
  fun collect(
    psiFile: PsiFile,
    caretOffset: Int,
    allowCrossFile: Boolean,
  ): List<ContextFragment> {
    val leaf = psiFile.findElementAt(caretOffset.coerceIn(0, (psiFile.textLength - 1).coerceAtLeast(0)))
      ?: return emptyList()
    val local = collectEnclosingDeclarations(leaf, caretOffset)
    return if (allowCrossFile) local + resolvedDefinitions(leaf, psiFile) else local
  }

  private fun resolvedDefinitions(leaf: PsiElement, psiFile: PsiFile): List<ContextFragment> {
    val candidates = generateSequence(leaf) { it.parent }.take(5).toList()
    val projectIndex = ProjectFileIndex.getInstance(project)
    return try {
      candidates
        .asSequence()
        .flatMap { it.references.asSequence() }
        .mapNotNull { reference -> runCatching { reference.resolve() }.getOrNull() }
        .filter { resolved ->
          val file = resolved.containingFile
          val virtualFile = file?.virtualFile
          file != null && file != psiFile && virtualFile != null && resolved.textRange != null &&
            projectIndex.isInContent(virtualFile)
        }
        .distinctBy { it.containingFile?.virtualFile?.path to it.textRange }
        .take(4)
        .mapNotNull { resolved ->
          val range = resolved.textRange ?: return@mapNotNull null
          val content = boundedPsiText(resolved.containingFile, range, range.startOffset, 180)
          if (content.isBlank()) return@mapNotNull null
          val name = (resolved as? PsiNamedElement)?.name ?: resolved.javaClass.simpleName
          ContextFragment(
            label = "Resolved symbol: $name (${resolved.containingFile.name})",
            content = content,
            priority = 78,
            maxTokens = 180,
            source = ContextFragmentSource.PSI_REFERENCE,
            sourceDetail = "psi-reference",
            origin = ContextOrigin(
              fileIdentity = resolved.containingFile.virtualFile.url,
              modificationStamp = PsiDocumentManager.getInstance(project)
                .getDocument(resolved.containingFile)
                ?.modificationStamp
                ?: resolved.containingFile.virtualFile.modificationStamp,
              crossFile = true,
            ),
          )
        }
        .toList()
    } catch (_: IndexNotReadyException) {
      emptyList()
    }
  }
}

internal fun collectEnclosingDeclarations(leaf: PsiElement, caretOffset: Int): List<ContextFragment> =
  generateSequence(leaf.parent) { it.parent }
    .filterIsInstance<PsiNamedElement>()
    .filter { it !is PsiFile && it.name?.isNotBlank() == true }
    .mapNotNull { element -> element.textRange?.let { range -> element to range } }
    .distinctBy { (element, range) -> element.name to range }
    .take(2)
    .withIndex()
    .mapNotNull { indexed ->
      val index = indexed.index
      val (element, range) = indexed.value
      val maxTokens = if (index == 0) 300 else 220
      val content = boundedPsiText(element.containingFile, range, caretOffset, maxTokens)
      if (content.isBlank()) return@mapNotNull null
      ContextFragment(
        label = if (index == 0) "Declaration at caret: ${element.name}" else "Enclosing declaration: ${element.name}",
        content = content,
        priority = if (index == 0) 98 else 82,
        maxTokens = maxTokens,
        source = ContextFragmentSource.PSI_LOCAL,
        sourceDetail = "psi",
        origin = element.containingFile?.let(::localOrigin),
      )
    }
    .toList()

private fun localOrigin(file: PsiFile): ContextOrigin = ContextOrigin(
  fileIdentity = file.virtualFile?.url ?: file.name,
  modificationStamp = file.virtualFile?.modificationStamp ?: file.modificationStamp,
  crossFile = false,
)

private fun boundedPsiText(
  psiFile: PsiFile?,
  range: TextRange,
  preferredOffset: Int,
  maxTokens: Int,
): String {
  val content = psiFile?.viewProvider?.contents ?: return ""
  val safeStart = range.startOffset.coerceIn(0, content.length)
  val safeEnd = range.endOffset.coerceIn(safeStart, content.length)
  val maxCharacters = maxTokens.coerceAtLeast(0) * 4
  if (safeEnd - safeStart <= maxCharacters) return content.subSequence(safeStart, safeEnd).toString()
  val center = preferredOffset.coerceIn(safeStart, safeEnd)
  val before = (maxCharacters * 2) / 3
  val start = (center - before).coerceAtLeast(safeStart)
  val end = (start + maxCharacters).coerceAtMost(safeEnd)
  return content.subSequence((end - maxCharacters).coerceAtLeast(safeStart), end).toString()
}
