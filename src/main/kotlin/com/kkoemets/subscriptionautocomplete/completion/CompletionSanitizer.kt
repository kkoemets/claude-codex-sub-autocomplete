package com.kkoemets.subscriptionautocomplete.completion

object CompletionSanitizer {
  fun sanitize(
    raw: String,
    prefix: String,
    suffix: String,
    maxTokens: Int,
    languageId: String = "",
    intent: CompletionIntent = CompletionIntent.ORDINARY,
  ): String {
    val normalizedRaw = raw.replace("\r\n", "\n")
    val preserveTrailingLineBreak = normalizedRaw.endsWith('\n') && suffix.isNotEmpty() && !suffix.startsWith('\n')
    var value = normalizedRaw
      .removeSurroundingFence()
      .removeFenceArtifactTail()
      .removePromptArtifactTail()
      .removeExplanationTail()
      .removeSpecialTokenTail()
      .trimEnd()
    if (value.isBlank()) return ""
    if (value.isNoInsertionExplanation()) return ""
    if (
      intent.isImplementation() &&
      (value.startsWithIntentComment() || value.echoesIntentComment(prefix))
    ) return ""
    value = removePrefixOverlap(value, prefix)
    value = removeSuffixOverlap(value, suffix)
    value = trimStructuredScalarOverflow(value, prefix, suffix, languageId)
    value = normalizeLeadingLineBreak(value, prefix)
    value = repairCommentBoundary(value, prefix)
    value = trimCommentOverflow(value, prefix)
    val bounded = value.trimEnd()
    if (bounded.length > CompletionOutputEnvelope.maxCharacters(maxTokens)) return ""
    if (intent.isImplementation() && bounded.startsWithIntentComment()) return ""
    return if (preserveTrailingLineBreak && bounded.isNotBlank()) "$bounded\n" else bounded
  }

  private fun String.startsWithIntentComment(): Boolean {
    val trimmed = trimStart()
    return trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*")
  }

  private fun String.echoesIntentComment(prefix: String): Boolean {
    val instruction = prefix.trimEnd().substringAfterLast('\n').trim()
    if (instruction.isBlank()) return false
    return lineSequence().any { line -> line.trim() == instruction }
  }

  private fun String.removeSurroundingFence(): String {
    val trimmed = trim()
    if (!trimmed.startsWith("```")) return this
    val firstNewline = trimmed.indexOf('\n')
    if (firstNewline < 0) return ""
    return trimmed.substring(firstNewline + 1).removeSuffix("```").trimEnd()
  }

  private fun String.removeSpecialTokenTail(): String {
    val tokenIndex = indexOf("<|endoftext|>")
    if (tokenIndex < 0) return this
    val lineStart = lastIndexOf('\n', tokenIndex)
    return if (lineStart >= 0) substring(0, lineStart) else substring(0, tokenIndex)
  }

  private fun String.removeFenceArtifactTail(): String {
    val artifact = FENCE_LINE.find(this) ?: return this
    return substring(0, artifact.range.first)
  }

  private fun String.removePromptArtifactTail(): String {
    val withoutCursor = replace(Regex("<CURSOR>", RegexOption.IGNORE_CASE), "")
    val artifact = PROMPT_ARTIFACT.find(withoutCursor) ?: return withoutCursor
    return withoutCursor.substring(0, artifact.range.first)
  }

  private fun String.removeExplanationTail(): String {
    val explanation = EXPLANATION_LINE.find(this) ?: return this
    return substring(0, explanation.range.first).trimEnd()
  }

  private fun String.isNoInsertionExplanation(): Boolean {
    val compact = trim().replace(WHITESPACE_RUN, " ")
    return NO_INSERTION_EXPLANATIONS.any { pattern -> pattern.containsMatchIn(compact) }
  }

  private fun removePrefixOverlap(completion: String, prefix: String): String {
    val max = minOf(completion.length, prefix.length, 240)
    val overlap = (max downTo MIN_OVERLAP_LENGTH).firstOrNull { length ->
      completion.startsWith(prefix.takeLast(length))
    } ?: 0
    return completion.drop(overlap)
  }

  private fun removeSuffixOverlap(completion: String, suffix: String): String {
    val max = minOf(completion.length, suffix.length, 240)
    val overlap = (max downTo 1).firstOrNull { length ->
      completion.endsWith(suffix.take(length)) &&
        (
          length >= MIN_OVERLAP_LENGTH ||
            suffix.first() in SAFE_SINGLE_CHARACTER_OVERLAPS &&
            hasExcessSingleCharacterBoundary(completion, suffix.first())
        )
    } ?: 0
    return completion.dropLast(overlap)
  }

  private fun hasExcessSingleCharacterBoundary(completion: String, boundary: Char): Boolean = when (boundary) {
    ')' -> completion.count { it == ')' } > completion.count { it == '(' }
    ']' -> completion.count { it == ']' } > completion.count { it == '[' }
    '}' -> completion.count { it == '}' } > completion.count { it == '{' }
    '"', '\'' -> completion.count { it == boundary } % 2 == 1
    ',', ';' -> true
    else -> false
  }

  private fun trimStructuredScalarOverflow(
    completion: String,
    prefix: String,
    suffix: String,
    languageId: String,
  ): String {
    val language = languageId.lowercase()
    val currentLine = prefix.substringAfterLast('\n').trim()
    val scalarCursor = when (language) {
      "json", "json5" -> currentLine.endsWith(':')
      "yaml", "yml" -> currentLine.endsWith(':') || currentLine == "-"
      else -> false
    }
    if (!scalarCursor) return completion
    val firstLine = completion.substringBefore('\n')
    val closesContainer = suffix.trimStart().firstOrNull() in setOf('}', ']')
    return if (language.startsWith("json") && closesContainer) {
      firstLine.trimEnd().removeSuffix(",")
    } else {
      firstLine
    }
  }

  private fun normalizeLeadingLineBreak(completion: String, prefix: String): String {
    if (!completion.startsWith('\n')) return completion
    val prefixBeforeCaret = prefix.trimEnd()
    val preserve = BLOCK_OPENING_SUFFIXES.any(prefixBeforeCaret::endsWith)
    val withoutLeadingBreaks = completion.dropWhile { it == '\n' || it == '\r' }
    return if (preserve) "\n$withoutLeadingBreaks" else withoutLeadingBreaks
  }

  private fun repairCommentBoundary(completion: String, prefix: String): String {
    val first = completion.firstOrNull() ?: return completion
    val last = prefix.lastOrNull() ?: return completion
    if (!first.isLetterOrDigit() || !last.isLetterOrDigit()) return completion
    if (!isNaturalLanguageContext(prefix)) return completion
    val followingWord = completion.takeWhile(Char::isLetter).lowercase()
    return if (looksLikePartialWord(followingWord)) completion else " $completion"
  }

  private fun looksLikePartialWord(followingWord: String): Boolean =
    followingWord in WORD_CONTINUATION_SUFFIXES

  private fun isNaturalLanguageContext(prefix: String): Boolean {
    val currentLine = prefix.substringAfterLast('\n').trimStart()
    val lineComment = LINE_COMMENT_PREFIXES.any(currentLine::startsWith)
    val blockComment = BLOCK_COMMENT_DELIMITERS.any { (open, close) ->
      prefix.lastIndexOf(open) > prefix.lastIndexOf(close)
    }
    val documentationString = DOCUMENTATION_DELIMITERS.any { delimiter ->
      prefix.split(delimiter).size % 2 == 0
    }
    return lineComment || blockComment || documentationString
  }

  private fun trimCommentOverflow(completion: String, prefix: String): String {
    val currentLine = prefix.substringAfterLast('\n').trimStart()
    val lineComment = LINE_COMMENT_PREFIXES.any(currentLine::startsWith)
    val documentationDelimiter = DOCUMENTATION_DELIMITERS.firstOrNull { delimiter ->
      prefix.split(delimiter).size % 2 == 0
    }
    if (lineComment) return completion.substringBefore('\n')
    if (documentationDelimiter == null) return completion
    val closingIndex = completion.indexOf(documentationDelimiter)
    return if (closingIndex >= 0) {
      completion.take(closingIndex + documentationDelimiter.length)
    } else {
      completion.substringBefore('\n')
    }
  }

  private const val MIN_OVERLAP_LENGTH = 2
  private val PROMPT_ARTIFACT = Regex(
    "</?[^>\\n]*(?:cursor|code_(?:before|after))[^>\\n]*>",
    RegexOption.IGNORE_CASE,
  )
  private val FENCE_LINE = Regex("(?m)^[ \\t]*```[^\\n]*$")
  private val EXPLANATION_LINE = Regex(
    "(?im)^[ \\t]*(?:the cursor\\b|the completion should\\b|wait,|looking at\\b|" +
      "explanation:|here(?:'s| is)\\b|i would\\b|sure[,!:])",
  )
  private val WHITESPACE_RUN = Regex("\\s+")
  private val NO_INSERTION_EXPLANATIONS = listOf(
    Regex(
      "(?i)^(?:the\\s+)?(?:yaml|yml|json|xml|html|document|file|configuration|config|code|structure)" +
        "(?:\\s+structure)?\\b.{0,160}\\b(?:already\\s+)?complete\\b",
    ),
    Regex(
      "(?i)\\bno\\s+(?:additional|further|other)\\s+(?:text|code|content|completion|changes?)\\b" +
        ".{0,160}\\b(?:insert(?:ed|ion)?|add(?:ed|ition)?|need(?:ed|s)?|require(?:d|s)?)\\b",
    ),
    Regex(
      "(?i)\\b(?:nothing|no\\s+insertion)\\b.{0,120}\\b(?:insert(?:ed)?|add(?:ed)?|need(?:ed|s)?|require(?:d|s)?)\\b",
    ),
  )
  private val BLOCK_OPENING_SUFFIXES = listOf("{", ":", "=>")
  private val SAFE_SINGLE_CHARACTER_OVERLAPS = setOf(')', ']', '}', '"', '\'', ',', ';')
  private val LINE_COMMENT_PREFIXES = listOf("//", "#", "--")
  private val BLOCK_COMMENT_DELIMITERS = listOf("/*" to "*/", "<!--" to "-->")
  private val DOCUMENTATION_DELIMITERS = listOf("\"\"\"", "'''")
  private val WORD_CONTINUATION_SUFFIXES = setOf(
    "s", "es", "ed", "er", "ers", "est", "ing", "ly", "y", "ies", "ied", "tion", "ation",
    "ions", "ations", "ment", "ments", "ness", "able", "ible", "al", "ally", "ity", "ities",
    "ous", "ive", "ize", "ise",
  )
}
