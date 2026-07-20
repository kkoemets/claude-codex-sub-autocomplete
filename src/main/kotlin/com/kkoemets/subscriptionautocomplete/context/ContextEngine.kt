package com.kkoemets.subscriptionautocomplete.context

import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service(Service.Level.PROJECT)
class ContextEngine(private val project: Project) {
  private val semanticCollector = SemanticContextCollector(project)
  private val editorContextCollector = EditorContextCollector(project)
  private val semanticCache = object : LinkedHashMap<SemanticCacheKey, SemanticCacheEntry>(32, 0.75f, true) {
    override fun removeEldestEntry(
      eldest: MutableMap.MutableEntry<SemanticCacheKey, SemanticCacheEntry>?,
    ): Boolean = size > 32
  }

  suspend fun gather(
    editor: Editor,
    requestedOffset: Int,
    requestedBudget: Int? = null,
    sharingPolicy: ContextSharingPolicy = ContextSharingPolicy(
      com.kkoemets.subscriptionautocomplete.completion.CompletionDestination.SUBSCRIPTION_PROCESS,
      allowCrossFile = false,
    ),
    mode: CompletionMode = CompletionMode.MANUAL,
  ): CompletionContext? {
    val snapshot = readAction {
      val document = editor.document
      val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@readAction null
      val text = document.text
      ContextSnapshot(
        psiFile = psiFile,
        path = psiFile.virtualFile?.path ?: psiFile.name,
        languageId = psiFile.language.id,
        fileName = psiFile.name,
        extension = psiFile.virtualFile?.extension?.lowercase().orEmpty(),
        text = text,
        offset = requestedOffset.coerceIn(0, text.length),
        documentModificationStamp = document.modificationStamp,
        structuralModificationCount = PsiModificationTracker.getInstance(project).modificationCount,
      )
    } ?: return null
    currentCoroutineContext().ensureActive()

    val settings = AutocompleteSettings.getInstance().snapshot()
    val budget = (requestedBudget ?: settings.contextTokenBudget)
      .coerceIn(600, 4000)
    val input = ContextInput(
      languageId = snapshot.languageId,
      fileName = snapshot.fileName,
      extension = snapshot.extension,
      text = snapshot.text,
      caretOffset = snapshot.offset,
    )
    val prefixBudget = (budget * 38) / 100
    val suffixBudget = (budget * 18) / 100
    val semanticBudget = budget - prefixBudget - suffixBudget
    val currentLine = AdapterSupport.currentLine(snapshot.text, snapshot.offset)
    val safeForSemanticScan =
      snapshot.text.length <= MAX_SEMANTIC_FILE_CHARS && currentLine.length <= MAX_LINE_CHARS
    val semanticResult = if (safeForSemanticScan) {
      semanticFragments(snapshot, input, currentLine.hashCode(), sharingPolicy)
    } else {
      SemanticResult(emptyList(), cacheHit = false)
    }
    currentCoroutineContext().ensureActive()

    val editorContext = editorContextCollector.collect(
      activeFile = snapshot.psiFile.virtualFile,
      mode = mode,
      settings = settings,
      sharingPolicy = sharingPolicy,
    )
    currentCoroutineContext().ensureActive()

    val activeOrigin = ContextOrigin(
      fileIdentity = snapshot.path,
      modificationStamp = snapshot.documentModificationStamp,
      crossFile = false,
    )
    val localFragments = semanticResult.fragments.map { fragment ->
        fragment.copy(
          content = SecretRedactor.redact(fragment.content),
          origin = fragment.origin ?: activeOrigin,
        )
      }
    val selectedFragments = ContextSourceBudgeter.select(
      local = localFragments,
      recent = editorContext.recentEdits,
      openTabs = editorContext.openTabs,
      semanticBudget,
    )
    val dependencies = selectedFragments.mapNotNull(ContextFragment::origin)
      .distinctBy { it.fileIdentity to it.modificationStamp }
    return CompletionContext(
      languageId = input.languageId,
      fileName = input.fileName,
      prefix = SecretRedactor.redact(TextBudget.before(snapshot.text, snapshot.offset, prefixBudget)),
      suffix = SecretRedactor.redact(TextBudget.after(snapshot.text, snapshot.offset, suffixBudget)),
      fragments = selectedFragments,
      semanticCacheHit = semanticResult.cacheHit,
      documentSnapshot = RequestDocumentSnapshot(
        fileIdentity = snapshot.path,
        text = snapshot.text,
        caretOffset = snapshot.offset,
        modificationStamp = snapshot.documentModificationStamp,
      ),
      dependencyFingerprint = ContextDependencyFingerprint(
        value = dependencyFingerprint(activeOrigin, dependencies, sharingPolicy.revision),
        dependencies = dependencies,
        hasCrossFileContent = dependencies.any(ContextOrigin::crossFile),
      ),
    )
  }

  private suspend fun semanticFragments(
    snapshot: ContextSnapshot,
    input: ContextInput,
    currentLineHash: Int,
    sharingPolicy: ContextSharingPolicy,
  ): SemanticResult {
    val adapterFragments = LanguageAdapterRegistry.collect(input)
    currentCoroutineContext().ensureActive()
    val now = System.nanoTime()
    val lineStart = input.text.lastIndexOf('\n', (snapshot.offset - 1).coerceAtLeast(0))
      .let { if (it < 0) 0 else it + 1 }
    val key = SemanticCacheKey(
      path = snapshot.path,
      lineStartOffset = lineStart,
      currentLineHash = currentLineHash,
      structuralModificationCount = snapshot.structuralModificationCount,
      contextSharingRevision = sharingPolicy.revision,
    )
    val cachedPsi = synchronized(semanticCache) {
      semanticCache[key]
        ?.takeIf { now - it.createdAtNanos <= SEMANTIC_CACHE_NANOS }
        ?.fragments
        .also { if (it == null) semanticCache.remove(key) }
    }
    if (cachedPsi != null) return SemanticResult(adapterFragments + cachedPsi, cacheHit = true)

    val psiFragments = readAction {
      if (snapshot.psiFile.isValid) {
        semanticCollector.collect(snapshot.psiFile, snapshot.offset, sharingPolicy.allowCrossFile)
      } else {
        emptyList()
      }
    }.map { fragment ->
      fragment.copy(content = TextBudget.trim(fragment.content, fragment.maxTokens).trim())
    }.filter { it.content.isNotBlank() }
    synchronized(semanticCache) {
      semanticCache[key] = SemanticCacheEntry(psiFragments, now)
    }
    return SemanticResult(adapterFragments + psiFragments, cacheHit = false)
  }

  companion object {
    private const val MAX_SEMANTIC_FILE_CHARS = 400_000
    private const val MAX_LINE_CHARS = 12_000
    private const val SEMANTIC_CACHE_NANOS = 10_000_000_000L

    fun getInstance(project: Project): ContextEngine = project.getService(ContextEngine::class.java)

    private fun dependencyFingerprint(
      activeOrigin: ContextOrigin,
      dependencies: List<ContextOrigin>,
      sharingRevision: String,
    ): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val values = (dependencies + activeOrigin)
        .distinctBy { it.fileIdentity to it.modificationStamp }
        .sortedBy(ContextOrigin::fileIdentity)
        .joinToString("\n") { origin ->
          "${origin.fileIdentity}\u0000${origin.modificationStamp}\u0000${origin.crossFile}"
        }
      return digest.digest("$sharingRevision\n$values".toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    }
  }

  private data class ContextSnapshot(
    val psiFile: PsiFile,
    val path: String,
    val languageId: String,
    val fileName: String,
    val extension: String,
    val text: String,
    val offset: Int,
    val documentModificationStamp: Long,
    val structuralModificationCount: Long,
  )

  private data class SemanticCacheKey(
    val path: String,
    val lineStartOffset: Int,
    val currentLineHash: Int,
    val structuralModificationCount: Long,
    val contextSharingRevision: String,
  )

  private data class SemanticCacheEntry(
    val fragments: List<ContextFragment>,
    val createdAtNanos: Long,
  )

  private data class SemanticResult(
    val fragments: List<ContextFragment>,
    val cacheHit: Boolean,
  )
}
