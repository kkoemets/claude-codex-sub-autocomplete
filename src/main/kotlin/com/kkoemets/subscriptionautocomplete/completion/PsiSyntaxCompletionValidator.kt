package com.kkoemets.subscriptionautocomplete.completion

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service(Service.Level.PROJECT)
class PsiSyntaxCompletionValidator(private val project: Project) : CompletionResultValidator {
  private val parseMutex = Mutex()

  override suspend fun validate(request: CompletionValidationRequest): CompletionValidation {
    if (request.fileType.isBinary) return CompletionValidation.Skipped("binary file")
    if (request.document.caretOffset !in 0..request.document.text.length) {
      return CompletionValidation.Skipped("invalid caret offset")
    }
    if (request.document.text.length + request.completion.length > MAX_FILE_CHARACTERS) {
      return CompletionValidation.Skipped("file exceeds syntax-validation cap")
    }
    if (DumbService.isDumb(project)) return CompletionValidation.Skipped("indices unavailable")
    currentCoroutineContext().ensureActive()
    return parseMutex.withLock {
      currentCoroutineContext().ensureActive()
      readAction {
        val baseline = createFile(request, request.document.text)
          ?: return@readAction CompletionValidation.Skipped("parser unavailable")
        if (baseline.language == PlainTextLanguage.INSTANCE) {
          return@readAction CompletionValidation.Skipped("parser unavailable")
        }
        if (baseline.viewProvider.languages.size != 1) {
          return@readAction CompletionValidation.Skipped("mixed-language file")
        }
        val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(baseline.language)
          ?: return@readAction CompletionValidation.Skipped("parser unavailable")
        val candidateText = request.document.text.substring(0, request.document.caretOffset) +
          request.completion + request.document.text.substring(request.document.caretOffset)
        val candidate = createFile(request, candidateText)
          ?: return@readAction CompletionValidation.Skipped("candidate parser unavailable")
        if (candidate.language != baseline.language || candidate.viewProvider.languages.size != 1) {
          return@readAction CompletionValidation.Skipped("candidate changed parser roots")
        }
        val introduced = PsiErrorDelta.introducedNearInsertion(
          errors(baseline),
          errors(candidate),
          request.document.caretOffset,
          request.completion.length,
        )
        if (introduced.isEmpty()) {
          CompletionValidation.Accept
        } else {
          CompletionValidation.Reject(
            reason = "new parser errors intersect the generated text or suffix boundary",
            errors = introduced,
            enforceable = CompletionValidationPolicy.isEnforceable(
              SyntaxParserIdentity(
                productCode = ApplicationInfo.getInstance().build.productCode.orEmpty(),
                build = ApplicationInfo.getInstance().build.asString(),
                fileType = request.fileType.name,
                parserClass = parserDefinition.javaClass.name,
              ),
              introduced,
            ),
          )
        }
      }
    }
  }

  private fun createFile(request: CompletionValidationRequest, text: String): PsiFile? = runCatching {
    PsiFileFactory.getInstance(project).createFileFromText(
      request.fileName,
      request.fileType,
      text,
      0,
      false,
      false,
    )
  }.getOrNull()

  private fun errors(file: PsiFile): List<SyntaxErrorFingerprint> =
    PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).map { error ->
      SyntaxErrorFingerprint(
        startOffset = error.textRange.startOffset,
        endOffset = error.textRange.endOffset,
        category = error.parent?.node?.elementType?.toString() ?: "ROOT",
      )
    }

  companion object {
    private const val MAX_FILE_CHARACTERS = 200_000

    fun getInstance(project: Project): PsiSyntaxCompletionValidator = project.getService(PsiSyntaxCompletionValidator::class.java)
  }
}
