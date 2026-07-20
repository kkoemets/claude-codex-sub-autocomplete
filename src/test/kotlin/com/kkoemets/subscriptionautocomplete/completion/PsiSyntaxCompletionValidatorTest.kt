package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.context.RequestDocumentSnapshot
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PsiSyntaxCompletionValidatorTest : BasePlatformTestCase() {
  override fun runInDispatchThread(): Boolean = false

  fun testValidJsonValueDoesNotIntroduceParserErrors() {
    runBlocking {
      val result = validateJson("true")

      assertIs<CompletionValidation.Accept>(result)
    }
  }

  fun testMalformedJsonValueIsReportedInShadowValidation() {
    runBlocking {
      val result = validateJson("true,, ")

      val rejected = assertIs<CompletionValidation.Reject>(result)
      assertFalse(rejected.enforceable)
    }
  }

  fun testValidCompletionDoesNotReReportAnUnrelatedBrokenBaseline() {
    runBlocking {
      val result = validate(
        fileName = "sample.json",
        textWithCaret = """{"broken":, "value": <caret>}""",
        completion = "true",
      )

      assertIs<CompletionValidation.Accept>(result)
    }
  }

  fun testMalformedClosingTokenReportsAnErrorAtTheSuffixBoundary() {
    runBlocking {
      val result = validateJson("true]")

      assertIs<CompletionValidation.Reject>(result)
    }
  }

  fun testUnsupportedPlainTextParserIsSkipped() {
    runBlocking {
      val result = validate("sample.txt", "value = <caret>", "42")

      val skipped = assertIs<CompletionValidation.Skipped>(result)
      assertEquals("parser unavailable", skipped.reason)
    }
  }

  fun testDumbModeSkipsValidation() {
    runBlocking {
      val result = DumbService.getInstance(project).runInDumbMode("syntax validation test") {
        validateJson("true")
      }

      val skipped = assertIs<CompletionValidation.Skipped>(result)
      assertEquals("indices unavailable", skipped.reason)
    }
  }

  fun testOversizedCandidateIsSkippedBeforeParsing() {
    runBlocking {
      val result = validate(
        fileName = "sample.json",
        textWithCaret = "<caret>" + " ".repeat(200_000),
        completion = "true",
      )

      val skipped = assertIs<CompletionValidation.Skipped>(result)
      assertEquals("file exceeds syntax-validation cap", skipped.reason)
    }
  }

  fun testInvalidCaretOffsetIsSkipped() {
    runBlocking {
      val fileType = FileTypeManager.getInstance().getFileTypeByFileName("sample.json")
      val result = PsiSyntaxCompletionValidator.getInstance(project).validate(
        CompletionValidationRequest(
          fileName = "sample.json",
          fileType = fileType,
          languageId = "JSON",
          document = RequestDocumentSnapshot("sample.json", "{}", 3, 1),
          completion = "true",
        ),
      )

      assertIs<CompletionValidation.Skipped>(result)
    }
  }

  fun testCancelledQueuedValidationDoesNotPoisonLaterRequests() {
    runBlocking {
      val cancelled = async(start = CoroutineStart.LAZY) { validateJson("true") }
      cancelled.cancel()
      assertFailsWith<CancellationException> { cancelled.await() }

      val results = List(8) { async { validateJson("true") } }.awaitAll()
      results.forEach { assertIs<CompletionValidation.Accept>(it) }
      assertIs<CompletionValidation.Reject>(validateJson("true,, "))
    }
  }

  private suspend fun validateJson(completion: String): CompletionValidation {
    return validate("sample.json", """{"value": <caret>}""", completion)
  }

  private suspend fun validate(
    fileName: String,
    textWithCaret: String,
    completion: String,
  ): CompletionValidation {
    val caret = textWithCaret.indexOf("<caret>")
    require(caret >= 0)
    val text = textWithCaret.replace("<caret>", "")
    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
    return PsiSyntaxCompletionValidator.getInstance(project).validate(
      CompletionValidationRequest(
        fileName = fileName,
        fileType = fileType,
        languageId = fileType.name,
        document = RequestDocumentSnapshot(
          fileIdentity = fileName,
          text = text,
          caretOffset = caret,
          modificationStamp = 1,
        ),
        completion = completion,
      ),
    )
  }
}
