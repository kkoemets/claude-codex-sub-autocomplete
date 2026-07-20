package com.kkoemets.subscriptionautocomplete.completion

import com.kkoemets.subscriptionautocomplete.context.CompletionContext
import com.kkoemets.subscriptionautocomplete.context.ContextFragment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionPromptTest {
  @Test
  fun `prompt contains bounded fill in the middle sections`() {
    val prompt = CompletionPromptBuilder.build(
      CompletionContext(
        languageId = "TypeScript",
        fileName = "main.ts",
        prefix = "const user = ",
        suffix = "\nexport {}",
        fragments = listOf(ContextFragment("Type", "interface User {}", 90, 20, "test")),
      ),
    )

    assertTrue(prompt.userPrompt.contains("<code_before_cursor>"))
    assertTrue(prompt.userPrompt.contains("<CURSOR>"))
    assertTrue(prompt.userPrompt.contains("interface User"))
    assertTrue(prompt.systemPrompt.contains("Do not explain"))
    assertTrue(prompt.systemPrompt.contains("not a quoted or escaped string"))
    assertTrue(prompt.systemPrompt.contains("valid for TypeScript"))
    assertTrue(prompt.userPrompt.contains("<cursor_kind>code</cursor_kind>"))
  }

  @Test
  fun `prompt keeps a line comment in natural language mode`() {
    val prompt = CompletionPromptBuilder.build(
      CompletionContext(
        languageId = "kotlin",
        fileName = "Strings.kt",
        prefix = "fun normalize() {\n  // Preserve null and",
        suffix = "\n}",
        fragments = emptyList(),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("Continue only its natural-language text"))
    assertTrue(prompt.systemPrompt.contains("never invent an operation"))
    assertTrue(prompt.userPrompt.contains("<cursor_kind>comment</cursor_kind>"))
    assertTrue(
      prompt.userPrompt.contains(
        "// Preserve null and</code_before_cursor><CURSOR><code_after_cursor>\n}",
      ),
    )
    assertTrue(prompt.systemPrompt.contains("Include leading whitespace"))
  }

  @Test
  fun `automatic prompt requests only one concise semantic unit`() {
    val prompt = CompletionPromptBuilder.build(
      CompletionContext(
        languageId = "kotlin",
        fileName = "Main.kt",
        prefix = "val answer = ",
        suffix = "",
        fragments = emptyList(),
      ),
      CompletionMode.AUTOMATIC,
    )

    assertTrue(prompt.systemPrompt.contains("one concise semantic unit"))
    assertTrue(prompt.systemPrompt.contains("never add sibling keys"))
    assertTrue(prompt.systemPrompt.contains("Never emit prompt-control markers"))
    assertEquals(CompletionMode.AUTOMATIC, prompt.mode)
  }

  @Test
  fun `typescript algorithm intent requests a complete balanced implementation`() {
    val prompt = automaticPrompt("TypeScript", "sort.ts", "// bubble sort\n")

    assertEquals(CompletionIntent.MULTILINE_IMPLEMENTATION, prompt.intent)
    assertTrue(prompt.userPrompt.contains("<completion_intent>multiline-implementation</completion_intent>"))
    assertTrue(prompt.userPrompt.contains("<cursor_kind>code</cursor_kind>"))
    assertTrue(prompt.systemPrompt.contains("complete, syntactically balanced declaration or block"))
    assertTrue(prompt.systemPrompt.contains("every required closing delimiter"))
    assertTrue(prompt.systemPrompt.contains("Do not stop early"))
    assertFalse(prompt.systemPrompt.contains("Prefer a short completion"))
    assertFalse(prompt.systemPrompt.contains("Continue only its natural-language text"))
  }

  @Test
  fun `typescript LRU instruction block requests every constraint as one complex implementation`() {
    val prompt = automaticPrompt(
      "TypeScript",
      "cache.ts",
      "// Implement an LRU (Least Recently Used) Cache class with O(1) time complexity for both get and put operations.\n" +
        "// It should use a Hash Map and a Doubly Linked List under the hood.\n",
    )

    assertEquals(CompletionIntent.COMPLEX_IMPLEMENTATION, prompt.intent)
    assertTrue(prompt.userPrompt.contains("<completion_intent>complex-implementation</completion_intent>"))
    assertTrue(prompt.systemPrompt.contains("satisfy every stated constraint"))
    assertTrue(prompt.systemPrompt.contains("directly required private helper type"))
    assertTrue(prompt.systemPrompt.contains("do not stop before all methods and delimiters are complete"))
  }

  @Test
  fun `python imperative intent requests the complete indented suite`() {
    val prompt = automaticPrompt("Python", "search.py", "# implement binary search\n")

    assertEquals(CompletionIntent.MULTILINE_IMPLEMENTATION, prompt.intent)
    assertTrue(prompt.systemPrompt.contains("complete indented suite"))
    assertTrue(prompt.systemPrompt.contains("stop after its balanced end"))
  }

  @Test
  fun `java intent requests a complete method from the blank code line`() {
    val prompt = automaticPrompt(
      "JAVA",
      "Csv.java",
      "// parse comma separated values\n",
    )

    assertEquals(CompletionIntent.MULTILINE_IMPLEMENTATION, prompt.intent)
    assertTrue(prompt.systemPrompt.contains("complete JAVA code"))
  }

  @Test
  fun `descriptive prose above a blank line does not enable multiline implementation`() {
    val prompt = automaticPrompt(
      "TypeScript",
      "sort.ts",
      "// This method keeps the original array unchanged.\n",
    )

    assertEquals(CompletionIntent.ORDINARY, prompt.intent)
    assertTrue(prompt.systemPrompt.contains("one concise semantic unit"))
    assertFalse(prompt.systemPrompt.contains("automatic implementation completion"))
  }

  @Test
  fun `inline intent-like comment preserves normal comment continuation`() {
    val prompt = automaticPrompt("TypeScript", "sort.ts", "// bubble sort")

    assertEquals(CompletionIntent.ORDINARY, prompt.intent)
    assertTrue(prompt.userPrompt.contains("<cursor_kind>comment</cursor_kind>"))
    assertTrue(prompt.systemPrompt.contains("Continue only its natural-language text"))
  }

  @Test
  fun `prompt recognizes an open documentation string`() {
    val prompt = CompletionPromptBuilder.build(
      CompletionContext(
        languageId = "Python",
        fileName = "retry.py",
        prefix = "def retry():\n    \"\"\"Retry the operation and",
        suffix = "\n    pass",
        fragments = emptyList(),
      ),
    )

    assertTrue(prompt.userPrompt.contains("<cursor_kind>comment</cursor_kind>"))
  }

  @Test
  fun `sanitizer removes fences and existing suffix`() {
    val result = CompletionSanitizer.sanitize(
      "```kotlin\nprintln(value)\n}\n```",
      "fun test() {\n",
      "\n}",
      100,
    )

    assertTrue(result.contains("println(value)"))
    assertFalse(result.contains("```"))
    assertFalse(result.endsWith("}"))
  }

  @Test
  fun `sanitizer preserves a single operator character`() {
    val result = CompletionSanitizer.sanitize(
      "= null)",
      "if (value =",
      " {",
      100,
    )

    assertEquals("= null)", result)
  }

  @Test
  fun `sanitizer drops code copied after a docstring continuation`() {
    val result = CompletionSanitizer.sanitize(
      " last error.\"\"\"\n    last_error = None\n    return operation()",
      "def retry():\n    \"\"\"Re-raise the",
      "\n    last_error = None",
      100,
    )

    assertEquals(" last error.\"\"\"", result)
  }

  @Test
  fun `sanitizer preserves a closing docstring on the next line`() {
    val result = CompletionSanitizer.sanitize(
      " last error.\n    \"\"\"\n    last_error = None",
      "def retry():\n    \"\"\"Re-raise the",
      "\n    last_error = None",
      100,
    )

    assertEquals(" last error.\n    \"\"\"", result)
  }

  @Test
  fun `sanitizer keeps line comment completions on the current line`() {
    val result = CompletionSanitizer.sanitize(
      " in flight.\nsubmit()",
      "// Ignore duplicate requests while one is",
      "\nif (submitting) return",
      100,
    )

    assertEquals(" in flight.", result)
  }

  @Test
  fun `sanitizer repairs a missing separator after a complete comment word`() {
    val result = CompletionSanitizer.sanitize(
      "install the dependencies required by the Telegram client",
      "# this will",
      "\nRUN apk update",
      100,
    )

    assertEquals(" install the dependencies required by the Telegram client", result)
  }

  @Test
  fun `sanitizer does not split a partial comment word`() {
    val result = CompletionSanitizer.sanitize(
      "ies before startup",
      "# Install the dependenc",
      "\nRUN apk update",
      100,
    )

    assertEquals("ies before startup", result)
  }

  @Test
  fun `sanitizer separates ordinary complete comment words`() {
    val result = CompletionSanitizer.sanitize(
      "when it fails",
      "// Retry the operation",
      "\nretry()",
      100,
    )

    assertEquals(" when it fails", result)
  }

  @Test
  fun `sanitizer preserves a structural leading newline`() {
    val result = CompletionSanitizer.sanitize(
      "\n  run()\n}",
      "if (ready) {",
      "\n}",
      100,
    )

    assertEquals("\n  run()", result)
  }

  @Test
  fun `sanitizer removes a duplicated single character suffix`() {
    val result = CompletionSanitizer.sanitize(
      "value)",
      "return transform(",
      ")",
      100,
    )

    assertEquals("value", result)
  }

  @Test
  fun `sanitizer removes Codex special token and its partial line`() {
    val result = CompletionSanitizer.sanitize(
      "            return value\n        convers<|endoftext|>",
      "if value % 2 == 0:\n",
      "\n    return None",
      100,
    )

    assertEquals("            return value", result)
  }

  @Test
  fun `sanitizer removes a leaked closing cursor tag`() {
    val result = CompletionSanitizer.sanitize(
      " db\n    </CURSOR>",
      "      -",
      "\n  db: {}",
      100,
    )

    assertEquals(" db", result)
  }

  @Test
  fun `sanitizer rejects an echoed prompt frame`() {
    val result = CompletionSanitizer.sanitize(
      "<code_after_cursor>\n  db: {}",
      "      -",
      "\n  db: {}",
      100,
    )

    assertEquals("", result)
  }

  @Test
  fun `sanitizer rejects an unknown cursor control frame`() {
    val result = CompletionSanitizer.sanitize(
      "<CURSOR_TEXT> </CURSOR_TEXT>\n\n db",
      "      -",
      "\n  db: {}",
      100,
    )

    assertEquals("", result)
  }

  @Test
  fun `sanitizer rejects a nested bare fence`() {
    val result = CompletionSanitizer.sanitize(
      "```yaml\n```\n```",
      "      -",
      "\n  db: {}",
      100,
    )

    assertEquals("", result)
  }

  @Test
  fun `sanitizer keeps code before an explanatory tail`() {
    val result = CompletionSanitizer.sanitize(
      " db\n\nWait, looking at the context more carefully",
      "      -",
      "\n  db: {}",
      100,
    )

    assertEquals(" db", result)
  }

  @Test
  fun `sanitizer rejects explanation instead of inserting prose`() {
    val result = CompletionSanitizer.sanitize(
      "The cursor should be completed with the database service name.",
      "      -",
      "\n  db: {}",
      100,
    )

    assertEquals("", result)
  }

  @Test
  fun `sanitizer keeps only the current JSON scalar`() {
    val result = CompletionSanitizer.sanitize(
      "true,\n  \"version\": \"1.0.0\"",
      "{\n  \"private\":",
      "\n}",
      100,
      "JSON",
    )

    assertEquals("true", result)
  }

  @Test
  fun `sanitizer keeps only the current YAML list item`() {
    val result = CompletionSanitizer.sanitize(
      " db\n    environment:\n      MODE: production",
      "services:\n  api:\n    depends_on:\n      -",
      "\n  db: {}",
      100,
      "yaml",
    )

    assertEquals(" db", result)
  }

  @Test
  fun `sanitizer preserves a required newline before an indented suffix`() {
    val result = CompletionSanitizer.sanitize(
      "    return await client.fetch()\n",
      "async function load() {\n  try {\n",
      "  } finally {\n    client.close()\n  }\n}\n",
      100,
      "TypeScript",
    )

    assertEquals("    return await client.fetch()\n", result)
  }

  @Test
  fun `sanitizer keeps balanced call parenthesis before an enclosing call suffix`() {
    val result = CompletionSanitizer.sanitize(
      "user.isActive()",
      "return users.stream().filter(user -> ",
      ").toList();\n",
      100,
      "JAVA",
    )

    assertEquals("user.isActive()", result)
  }

  @Test
  fun `multiline intent rejects over-budget partial output instead of truncating it`() {
    val result = CompletionSanitizer.sanitize(
      "function bubbleSort(values: number[]) {\n" + "  values.sort()\n".repeat(20) + "}\n",
      "// bubble sort\n",
      "",
      16,
      "TypeScript",
      CompletionIntent.MULTILINE_IMPLEMENTATION,
    )

    assertEquals("", result)
  }

  @Test
  fun `sanitizer preserves a balanced implementation beyond the old four character estimate`() {
    val implementation = "function populate(values: number[]) {\n" +
      "  values.push(1);\n".repeat(140) +
      "}\n"

    val result = CompletionSanitizer.sanitize(
      implementation,
      "// populate an array\n",
      "",
      512,
      "TypeScript",
      CompletionIntent.COMPLEX_IMPLEMENTATION,
    )

    assertEquals(implementation.trimEnd(), result)
    assertTrue(result.length > 2_048)
  }

  @Test
  fun `sanitizer rejects an implementation beyond the output safety envelope`() {
    val implementation = "function populate(values: number[]) {\n" +
      "  values.push(1);\n".repeat(240) +
      "}\n"

    val result = CompletionSanitizer.sanitize(
      implementation,
      "// populate an array\n",
      "",
      512,
      "TypeScript",
      CompletionIntent.COMPLEX_IMPLEMENTATION,
    )

    assertEquals("", result)
    assertTrue(implementation.length > 4_096)
  }

  @Test
  fun `multiline intent rejects an echoed instruction comment`() {
    val result = CompletionSanitizer.sanitize(
      "// bubble sort\nfunction bubbleSort() {}",
      "// bubble sort\n",
      "",
      192,
      "TypeScript",
      CompletionIntent.MULTILINE_IMPLEMENTATION,
    )

    assertEquals("", result)
  }

  @Test
  fun `multiline intent rejects a trailing echoed instruction comment`() {
    val result = CompletionSanitizer.sanitize(
      "function bubbleSort() {}\n// bubble sort",
      "// bubble sort\n",
      "",
      192,
      "TypeScript",
      CompletionIntent.MULTILINE_IMPLEMENTATION,
    )

    assertEquals("", result)
  }

  private fun automaticPrompt(languageId: String, fileName: String, prefix: String): CompletionPrompt =
    CompletionPromptBuilder.build(
      CompletionContext(
        languageId = languageId,
        fileName = fileName,
        prefix = prefix,
        suffix = "",
        fragments = emptyList(),
      ),
      CompletionMode.AUTOMATIC,
    )
}
