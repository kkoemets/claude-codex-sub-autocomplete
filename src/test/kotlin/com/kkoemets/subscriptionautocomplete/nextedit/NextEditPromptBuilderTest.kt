package com.kkoemets.subscriptionautocomplete.nextedit

import kotlin.test.Test
import kotlin.test.assertTrue

class NextEditPromptBuilderTest {
  @Test
  fun `prompt uses opaque bounded targets and never includes local identities`() {
    val targets = (1..5).map { index ->
      NextEditTarget(
        id = "target-$index",
        displayName = "file-$index.ts",
        languageId = "TypeScript",
        excerpt = "const value$index = true\n".repeat(200),
        currentText = "/Users/private/workspace/secret-$index.ts",
        modificationStamp = index.toLong(),
        file = null,
      )
    }
    val prompt = NextEditPromptBuilder.build(
      NextEditRequestContext(
        activeFileName = "active.ts",
        languageId = "TypeScript",
        prefix = "p".repeat(3_000),
        suffix = "s".repeat(2_000),
        activeEditor = null,
        activeFile = null,
        activeFileIdentity = null,
        activeModificationStamp = 7,
        activeCaretOffset = 3_000,
        targets = targets,
      ),
    )

    assertTrue(prompt.systemPrompt.contains("Never call tools"))
    assertTrue(!prompt.userPrompt.contains("target-0"))
    assertTrue(prompt.userPrompt.contains("target-1"))
    assertTrue(prompt.userPrompt.contains("target-3"))
    assertTrue(!prompt.userPrompt.contains("target-5"))
    assertTrue(!prompt.userPrompt.contains("/Users/private"))
    assertTrue(prompt.userPrompt.length < 10_000)
  }
}
