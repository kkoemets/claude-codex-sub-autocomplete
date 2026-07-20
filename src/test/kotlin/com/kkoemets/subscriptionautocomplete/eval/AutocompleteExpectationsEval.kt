package com.kkoemets.subscriptionautocomplete.eval

import com.google.gson.GsonBuilder
import com.kkoemets.subscriptionautocomplete.completion.CompletionSanitizer
import com.kkoemets.subscriptionautocomplete.completion.CompletionTriggerPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

object AutocompleteExpectationsEval {
  @JvmStatic
  fun main(args: Array<String>) {
    val startedAt = Instant.now()
    val dataset = EvalDatasetLoader.load()
    val insertionResults = INSERTION_CASES.map(::evaluateInsertion)
    val triggerResults = TRIGGER_CASES.map(::evaluateTrigger)
    val report = ExpectationsReport(
      startedAt = startedAt.toString(),
      completedAt = Instant.now().toString(),
      corpus = CorpusSummary(dataset.version, dataset.cases.size, dataset.taskCounts()),
      insertionResults = insertionResults,
      triggerResults = triggerResults,
    )
    val outputDirectory = Path.of(
      System.getProperty("eval.outputDir", "build/reports/autocomplete-expectations"),
    )
    Files.createDirectories(outputDirectory)
    val suffix = startedAt.toString().replace(':', '-').substringBefore('.')
    val jsonPath = outputDirectory.resolve("expectations-$suffix.json")
    val markdownPath = outputDirectory.resolve("expectations-$suffix.md")
    Files.writeString(jsonPath, GsonBuilder().setPrettyPrinting().create().toJson(report))
    Files.writeString(markdownPath, report.toMarkdown())

    insertionResults.forEach { result ->
      println("INSERT  ${result.id.padEnd(30)} ${if (result.passed) "PASS" else "FAIL"}")
    }
    triggerResults.forEach { result ->
      println("TRIGGER ${result.id.padEnd(30)} ${if (result.passed) "PASS" else "FAIL"}")
    }
    println("\nReports:\n$jsonPath\n$markdownPath")
  }

  private fun evaluateInsertion(case: InsertionCase): InsertionResult {
    val completion = CompletionSanitizer.sanitize(
      case.raw,
      case.prefix,
      case.suffix,
      64,
      case.languageId,
    )
    val actual = case.prefix + completion + case.suffix
    return InsertionResult(
      id = case.id,
      category = case.category,
      passed = actual == case.expected,
      completionLength = completion.length,
      expectedLength = case.expected.length,
      actualLength = actual.length,
    )
  }

  private fun evaluateTrigger(case: TriggerCase): TriggerResult {
    val actual = CompletionTriggerPolicy.shouldRequest(case.text, case.caretOffset, case.typed)
    return TriggerResult(case.id, actual == case.expected, case.expected, actual)
  }

  private val INSERTION_CASES = listOf(
    insertion(
      "comment-conjunction-space",
      "comment-boundary",
      "# this will",
      "install dependencies",
      "\nRUN apk update",
      "# this will install dependencies\nRUN apk update",
    ),
    insertion(
      "comment-noun-space",
      "comment-boundary",
      "// Retry the operation",
      "when it fails",
      "\nretry()",
      "// Retry the operation when it fails\nretry()",
    ),
    insertion(
      "partial-comment-word",
      "comment-boundary",
      "# Install the dependenc",
      "ies before startup",
      "\nRUN apk update",
      "# Install the dependencies before startup\nRUN apk update",
    ),
    insertion("partial-code-word", "partial-word", "val res", "ult", " = compute()", "val result = compute()"),
    insertion(
      "line-comment-overflow",
      "comment-boundary",
      "// Request is",
      " in flight.\nsubmit()",
      "\nif (submitting) return",
      "// Request is in flight.\nif (submitting) return",
    ),
    insertion(
      "docstring-overflow",
      "comment-boundary",
      "def retry():\n    \"\"\"Re-raise the",
      " last error.\n    \"\"\"\n    retry()",
      "\n    last_error = None",
      "def retry():\n    \"\"\"Re-raise the last error.\n    \"\"\"\n    last_error = None",
    ),
    insertion(
      "javascript-leading-newline",
      "multiline",
      "if (ready) {",
      "\n  run()",
      "\n}",
      "if (ready) {\n  run()\n}",
    ),
    insertion(
      "python-leading-newline",
      "multiline",
      "if ready:",
      "\n    run()",
      "\nnext_step()",
      "if ready:\n    run()\nnext_step()",
    ),
    insertion(
      "multiline-existing-brace",
      "suffix-preservation",
      "if (ready) {",
      "\n  run()\n}",
      "\n}",
      "if (ready) {\n  run()\n}",
    ),
    insertion(
      "single-paren-overlap",
      "suffix-preservation",
      "return transform(",
      "value)",
      ")",
      "return transform(value)",
    ),
    insertion(
      "single-quote-overlap",
      "suffix-preservation",
      "const name = \"",
      "Ada\"",
      "\"",
      "const name = \"Ada\"",
    ),
    insertion(
      "single-comma-overlap",
      "suffix-preservation",
      "{\n  \"enabled\": ",
      "true,",
      ",\n  \"name\": \"api\"\n}",
      "{\n  \"enabled\": true,\n  \"name\": \"api\"\n}",
    ),
    insertion(
      "html-closing-tag-overlap",
      "suffix-preservation",
      "<button>",
      "Save</button>",
      "</button>",
      "<button>Save</button>",
    ),
    insertion(
      "multiline-suffix-overlap",
      "suffix-preservation",
      "fun ready(): Boolean {",
      "\n  return true\n}",
      "\n}",
      "fun ready(): Boolean {\n  return true\n}",
    ),
    insertion(
      "operator-preservation",
      "operators",
      "const active = enabled ",
      "&& ready",
      "\n",
      "const active = enabled && ready\n",
    ),
    insertion(
      "block-comment-space",
      "comment-boundary",
      "/* Cache responses",
      "to reduce latency */",
      "\nfetch()",
      "/* Cache responses to reduce latency */\nfetch()",
    ),
    insertion(
      "sql-comment-space",
      "comment-boundary",
      "-- Return rows",
      "when active",
      "\nSELECT * FROM users",
      "-- Return rows when active\nSELECT * FROM users",
    ),
    insertion(
      "unicode-comment-space",
      "comment-boundary",
      "# Prüfe Eingaben",
      "bevor sie gespeichert werden",
      "\nvalidate()",
      "# Prüfe Eingaben bevor sie gespeichert werden\nvalidate()",
    ),
    insertion(
      "markdown-fence-removal",
      "cleanup",
      "return ",
      "```kotlin\nvalue\n```",
      "",
      "return value",
    ),
    insertion(
      "special-token-removal",
      "cleanup",
      "return ",
      "value\nnoise<|endoftext|>",
      "",
      "return value",
    ),
    insertion("cursor-token-removal", "cleanup", "return ", "<CURSOR>value", "", "return value"),
    insertion(
      "closing-cursor-removal",
      "cleanup",
      "      -",
      " db\n    </CURSOR>",
      "\n  db: {}",
      "      - db\n  db: {}",
    ),
    insertion(
      "prompt-frame-rejection",
      "cleanup",
      "      -",
      "<code_after_cursor>\n  db: {}",
      "\n  db: {}",
      "      -\n  db: {}",
    ),
    insertion(
      "unknown-cursor-frame-rejection",
      "cleanup",
      "      -",
      "<CURSOR_TEXT> </CURSOR_TEXT>\n\n db",
      "\n  db: {}",
      "      -\n  db: {}",
    ),
    insertion(
      "nested-fence-rejection",
      "cleanup",
      "      -",
      "```yaml\n```\n```",
      "\n  db: {}",
      "      -\n  db: {}",
    ),
    insertion(
      "json-scalar-overflow",
      "structured-scalar",
      "{\n  \"private\":",
      "true,\n  \"version\": \"1.0.0\"",
      "\n}",
      "{\n  \"private\":true\n}",
      "JSON",
    ),
    insertion(
      "yaml-list-item-overflow",
      "structured-scalar",
      "services:\n  api:\n    depends_on:\n      -",
      " db\n    environment:\n      MODE: production",
      "\n  db: {}",
      "services:\n  api:\n    depends_on:\n      - db\n  db: {}",
      "yaml",
    ),
    insertion(
      "explanation-tail-removal",
      "cleanup",
      "      -",
      " db\n\nWait, looking at the context more carefully",
      "\n  db: {}",
      "      - db\n  db: {}",
    ),
    insertion(
      "cursor-explanation-rejection",
      "cleanup",
      "      -",
      "The cursor should be completed with db.",
      "\n  db: {}",
      "      -\n  db: {}",
    ),
    insertion("prefix-echo-removal", "overlap", "return ", "return value", "", "return value"),
    insertion(
      "suffix-line-overlap",
      "overlap",
      "if (ready) {\n",
      "  run()\n}",
      "\n}",
      "if (ready) {\n  run()\n}",
    ),
    insertion(
      "crlf-provider-normalization",
      "cleanup",
      "if (ready) {",
      "\r\n  run()",
      "\n}",
      "if (ready) {\n  run()\n}",
    ),
    insertion("explanation-rejection", "cleanup", "return ", "Here is the result: value", "", "return "),
  )

  private val TRIGGER_CASES = listOf(
    trigger("comment", "// explain retry", 16, "y", true),
    trigger("assignment-space", "const result = ", 15, " ", true),
    trigger("member-dot", "user.", 5, ".", true),
    trigger("identifier", "ret", 3, "t", true),
    trigger("newline", "value\n", 6, "\n", true),
    trigger("comma", "call(first,", 11, ",", true),
    trigger("opening-paren", "if (", 4, "(", true),
    trigger("blank-line", "  ", 2, " ", false),
    trigger("middle-identifier", "returnValue", 3, "t", false),
    trigger("deletion", "return", 6, "\b", false),
    trigger("empty-event", "return", 6, "", false),
  )

  private fun insertion(
    id: String,
    category: String,
    prefix: String,
    raw: String,
    suffix: String,
    expected: String,
    languageId: String = "",
  ) = InsertionCase(id, category, prefix, raw, suffix, expected, languageId)

  private fun trigger(
    id: String,
    text: String,
    caretOffset: Int,
    typed: String,
    expected: Boolean,
  ) = TriggerCase(id, text, caretOffset, typed, expected)
}

private data class InsertionCase(
  val id: String,
  val category: String,
  val prefix: String,
  val raw: String,
  val suffix: String,
  val expected: String,
  val languageId: String,
)

private data class InsertionResult(
  val id: String,
  val category: String,
  val passed: Boolean,
  val completionLength: Int,
  val expectedLength: Int,
  val actualLength: Int,
)

private data class TriggerCase(
  val id: String,
  val text: String,
  val caretOffset: Int,
  val typed: String,
  val expected: Boolean,
)

private data class TriggerResult(
  val id: String,
  val passed: Boolean,
  val expected: Boolean,
  val actual: Boolean,
)

private data class ExpectationsReport(
  val startedAt: String,
  val completedAt: String,
  val corpus: CorpusSummary,
  val insertionResults: List<InsertionResult>,
  val triggerResults: List<TriggerResult>,
) {
  fun toMarkdown(): String = buildString {
    val insertionPassed = insertionResults.count(InsertionResult::passed)
    val triggerPassed = triggerResults.count(TriggerResult::passed)
    appendLine("# Autocomplete expectations evaluation")
    appendLine()
    appendLine("Started: $startedAt")
    appendLine()
    appendLine(
      "Corpus: `${corpus.version}`; ${corpus.cases} cases; " +
        corpus.taskCounts.entries.joinToString { (kind, count) -> "$kind=$count" },
    )
    appendLine()
    appendLine("| Gate | Result | Required | Status |")
    appendLine("| --- | ---: | ---: | --- |")
    appendLine(
      "| Exact insertion boundaries | $insertionPassed/${insertionResults.size} | " +
        "${insertionResults.size}/${insertionResults.size} | " +
        "${if (insertionPassed == insertionResults.size) "PASS" else "FAIL"} |",
    )
    appendLine(
      "| Automatic trigger policy | $triggerPassed/${triggerResults.size} | " +
        "${triggerResults.size}/${triggerResults.size} | " +
        "${if (triggerPassed == triggerResults.size) "PASS" else "FAIL"} |",
    )
    appendLine()
    appendLine("## Insertion cases")
    appendLine()
    insertionResults.forEach { result ->
      appendLine("### ${result.id}: ${if (result.passed) "PASS" else "FAIL"}")
      appendLine()
      appendLine("Category: `${result.category}`")
      if (!result.passed) {
        appendLine()
        appendLine(
          "Expected document length `${result.expectedLength}`; actual `${result.actualLength}`; " +
            "sanitized completion `${result.completionLength}`. Source text is intentionally omitted.",
        )
      }
      appendLine()
    }
    appendLine("## Trigger cases")
    appendLine()
    triggerResults.forEach { result ->
      appendLine(
        "- `${result.id}`: ${if (result.passed) "PASS" else "FAIL"} " +
          "(expected `${result.expected}`, got `${result.actual}`)",
      )
    }
  }
}

private data class CorpusSummary(
  val version: String,
  val cases: Int,
  val taskCounts: Map<EvalTaskKind, Int>,
)
