package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.context.RequestDocumentSnapshot
import com.intellij.openapi.fileTypes.FileType

sealed interface CompletionValidation {
  data object Accept : CompletionValidation

  data class Reject(
    val reason: String,
    val errors: List<SyntaxErrorFingerprint>,
    val enforceable: Boolean,
  ) : CompletionValidation

  data class Skipped(val reason: String) : CompletionValidation
}

data class CompletionValidationRequest(
  val fileName: String,
  val fileType: FileType,
  val languageId: String,
  val document: RequestDocumentSnapshot,
  val completion: String,
)

data class SyntaxErrorFingerprint(
  val startOffset: Int,
  val endOffset: Int,
  val category: String,
)

fun interface CompletionResultValidator {
  suspend fun validate(request: CompletionValidationRequest): CompletionValidation

  companion object {
    val NONE = CompletionResultValidator { CompletionValidation.Skipped("disabled") }
  }
}
