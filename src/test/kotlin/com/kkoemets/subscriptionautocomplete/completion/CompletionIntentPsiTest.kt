package com.kkoemets.subscriptionautocomplete.completion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionIntentPsiTest : BasePlatformTestCase() {
  fun testJavaInstructionCommentBlockIsAcceptedAtTheImmediateBlankLine() {
    myFixture.configureByText(
      "Example.java",
      """
        class Example {
          // Implement an LRU Cache class with O(1) get and put operations.
          // It should use a Hash Map and a Doubly Linked List.
          <caret>
        }
      """.trimIndent(),
    )

    assertEquals(
      CompletionIntent.COMPLEX_IMPLEMENTATION,
      CompletionIntentClassifier.classify(
        myFixture.editor.document.charsSequence,
        myFixture.editor.caretModel.offset,
        languageId = "Java",
      ),
    )
    assertTrue(
      isPsiLineCommentIntent(
        myFixture.file,
        myFixture.editor.document.charsSequence,
        myFixture.editor.caretModel.offset,
      ),
    )
  }

  fun testBlockCommentAndTextBlockCannotActivateCodeIntent() {
    listOf(
      """
        class Example {
          /*
          // bubble sort
          <caret>
          */
        }
      """.trimIndent(),
      "class Example {\n  String value = \"\"\"\n// bubble sort\n<caret>\n\"\"\";\n}",
    ).forEach { text ->
      myFixture.configureByText("Example.java", text)

      assertFalse(
        isPsiLineCommentIntent(
          myFixture.file,
          myFixture.editor.document.charsSequence,
          myFixture.editor.caretModel.offset,
        ),
      )
    }
  }
}
