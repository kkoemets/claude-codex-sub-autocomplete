package com.kkoemets.subscriptionautocomplete.context

data class ContextInput(
  val languageId: String,
  val fileName: String,
  val extension: String,
  val text: String,
  val caretOffset: Int,
)

enum class ContextFragmentSource {
  LANGUAGE_ADAPTER,
  PSI_LOCAL,
  PSI_REFERENCE,
  RECENT_EDIT,
  OPEN_TAB,
  TEST,
}

data class ContextOrigin(
  val fileIdentity: String,
  val modificationStamp: Long,
  val crossFile: Boolean,
)

data class ContextFragment(
  val label: String,
  val content: String,
  val priority: Int,
  val maxTokens: Int,
  val source: ContextFragmentSource,
  val sourceDetail: String = "",
  val origin: ContextOrigin? = null,
) {
  constructor(
    label: String,
    content: String,
    priority: Int,
    maxTokens: Int,
    source: String,
  ) : this(
    label,
    content,
    priority,
    maxTokens,
    legacySource(source),
    source,
  )

  companion object {
    private fun legacySource(value: String): ContextFragmentSource = when (value) {
      "psi" -> ContextFragmentSource.PSI_LOCAL
      "psi-reference" -> ContextFragmentSource.PSI_REFERENCE
      "recent-edit" -> ContextFragmentSource.RECENT_EDIT
      "open-tab" -> ContextFragmentSource.OPEN_TAB
      "test" -> ContextFragmentSource.TEST
      else -> ContextFragmentSource.LANGUAGE_ADAPTER
    }
  }
}

data class RequestDocumentSnapshot(
  val fileIdentity: String,
  val text: String,
  val caretOffset: Int,
  val modificationStamp: Long,
)

data class ContextDependencyFingerprint(
  val value: String,
  val dependencies: List<ContextOrigin>,
  val hasCrossFileContent: Boolean,
) {
  companion object {
    val EMPTY = ContextDependencyFingerprint("", emptyList(), hasCrossFileContent = false)
  }
}

data class CompletionContext(
  val languageId: String,
  val fileName: String,
  val prefix: String,
  val suffix: String,
  val fragments: List<ContextFragment>,
  val semanticCacheHit: Boolean = false,
  val documentSnapshot: RequestDocumentSnapshot? = null,
  val dependencyFingerprint: ContextDependencyFingerprint = ContextDependencyFingerprint.EMPTY,
) {
  fun formattedFragments(): String = fragments.joinToString("\n\n") {
    "--- ${it.label} ---\n${it.content}"
  }
}

interface LanguageContextAdapter {
  fun supports(input: ContextInput): Boolean

  fun collect(input: ContextInput): List<ContextFragment>
}
