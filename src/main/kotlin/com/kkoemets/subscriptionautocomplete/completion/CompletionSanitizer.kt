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
    val proseDocument = languageId.lowercase() in PROSE_LANGUAGES
    val lineCommentPrefixes = lineCommentPrefixes(languageId)
    // In documents and comments a sentence such as "Here is how to install it" is the content.
    val proseContext = proseDocument || isNaturalLanguageContext(prefix, lineCommentPrefixes)
    var value = normalizedRaw
      .removeSurroundingFence()
      .removeFenceArtifactTail()
      .removePromptArtifactTail()
      .let { if (proseContext) it else it.removeLeadingReasoning().removeExplanationTail() }
      .removeSpecialTokenTail()
      .trimEnd()
    if (value.isBlank()) return ""
    if (value.isNoInsertionExplanation()) return ""
    // A string literal may hold a sentence, so only an answer outside strings is rejected outright.
    if (!proseContext && !isInsideStringLiteral(prefix) && value.isReasoningProse()) return ""
    if (
      intent.isImplementation() &&
      (value.startsWithIntentComment() || value.echoesIntentComment(prefix))
    ) return ""
    value = removePrefixOverlap(value, prefix)
    value = removeSuffixOverlap(value, suffix)
    value = trimStructuredScalarOverflow(value, prefix, suffix, languageId)
    value = normalizeLeadingLineBreak(value, prefix)
    value = repairCommentBoundary(value, prefix, lineCommentPrefixes)
    value = trimCommentOverflow(value, prefix, lineCommentPrefixes)
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
    val withoutCursor = replace("<CURSOR>", "")
    val artifact = PROMPT_ARTIFACT.find(withoutCursor) ?: return withoutCursor
    return withoutCursor.substring(0, artifact.range.first)
  }

  /**
   * Models sometimes think aloud before the code: "The cursor sits inside the class attribute, so
   * the completion is a class name.\n\nnav-link". The reasoning paragraph is dropped and the code
   * after it kept; previously the explanation rule discarded both.
   */
  private fun String.removeLeadingReasoning(): String {
    val split = indexOf("\n\n")
    if (split < 0) return this
    val rest = substring(split).trimStart('\n')
    return if (rest.isNotBlank() && substring(0, split).isReasoningProse()) rest else this
  }

  /**
   * One or more plain sentences about the task rather than code: several words, no code
   * punctuation outside `inline code`, ending like a sentence, and talking about the request
   * itself (the cursor, the intent, what is likely or needed).
   */
  private fun String.isReasoningProse(): Boolean {
    val text = trim().replace(INLINE_CODE, "code")
    if (text.length < 20 || text.any { it in CODE_PUNCTUATION }) return false
    if (!text.first().isUpperCase() || text.last() !in ".:!?") return false
    if (text.split(WHITESPACE_RUN).size < 6) return false
    return REASONING_WORDS.containsMatchIn(text)
  }

  /** An odd number of double quotes on the caret line means the caret is inside a string. */
  private fun isInsideStringLiteral(prefix: String): Boolean {
    val line = prefix.substringAfterLast('\n')
    return line.count { it == '"' } % 2 == 1 || line.count { it == '\'' } % 2 == 1 && !line.contains('#')
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

  /**
   * Drops the end of the completion when it repeats the text that already follows the caret.
   *
   * A repeated run of closing brackets or quotes is only a repeat when the completion has that many
   * closers too many. `len(items.get(key))` in front of an existing `))` is balanced on its own, so
   * its closers belong to it and removing them would leave the result unbalanced.
   */
  private fun removeSuffixOverlap(completion: String, suffix: String): String {
    val max = minOf(completion.length, suffix.length, 240)
    val overlap = (max downTo 1).firstOrNull { length ->
      val repeated = suffix.take(length)
      completion.endsWith(repeated) && when {
        repeated.all { it in CLOSING_BOUNDARIES } -> repeated.all { hasExcessBoundary(completion, it, repeated) }
        length >= MIN_OVERLAP_LENGTH -> true
        else -> repeated.first() in SAFE_SINGLE_CHARACTER_OVERLAPS
      }
    } ?: 0
    return completion.dropLast(overlap)
  }

  private fun hasExcessBoundary(completion: String, boundary: Char, repeated: String): Boolean {
    val needed = repeated.count { it == boundary }
    return when (boundary) {
      ')' -> completion.count { it == ')' } - completion.count { it == '(' } >= needed
      ']' -> completion.count { it == ']' } - completion.count { it == '[' } >= needed
      '}' -> completion.count { it == '}' } - completion.count { it == '{' } >= needed
      // An odd number of quotes means the completion closes a string the prefix opened.
      else -> needed == 1 && completion.count { it == boundary } % 2 == 1
    }
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
    // A nested object, array or block starts here; the first line alone is not the value.
    val opensContainer = firstLine.trim().let { it.isEmpty() || it.endsWith('{') || it.endsWith('[') }
    if (opensContainer) return completion
    val closesContainer = suffix.trimStart().firstOrNull() in setOf('}', ']')
    return if (language.startsWith("json") && closesContainer) {
      firstLine.trimEnd().removeSuffix(",")
    } else {
      firstLine
    }
  }

  /**
   * A leading line break is noise when the caret already sits on an empty line. After text on the
   * caret line it is what separates the completion from that text and must stay, otherwise
   * `foo()` and a completion of a new statement are glued into `foo()bar()`.
   */
  private fun normalizeLeadingLineBreak(completion: String, prefix: String): String {
    if (!completion.startsWith('\n')) return completion
    val withoutLeadingBreaks = completion.dropWhile { it == '\n' || it == '\r' }
    val caretLine = prefix.substringAfterLast('\n')
    val opensBlock = BLOCK_OPENING_SUFFIXES.any(prefix.trimEnd()::endsWith)
    // Trailing whitespace on the caret line means the user is still writing that line.
    val continuesLine = caretLine.isBlank() || (caretLine.last().isWhitespace() && !opensBlock)
    return if (continuesLine && !opensBlock) withoutLeadingBreaks else "\n$withoutLeadingBreaks"
  }

  private fun repairCommentBoundary(completion: String, prefix: String, lineCommentPrefixes: List<String>): String {
    val first = completion.firstOrNull() ?: return completion
    val last = prefix.lastOrNull() ?: return completion
    if (!first.isLetterOrDigit() || !last.isLetterOrDigit()) return completion
    if (!isNaturalLanguageContext(prefix, lineCommentPrefixes)) return completion
    val followingWord = completion.takeWhile(Char::isLetter).lowercase()
    return if (looksLikePartialWord(followingWord)) completion else " $completion"
  }

  private fun looksLikePartialWord(followingWord: String): Boolean =
    followingWord in WORD_CONTINUATION_SUFFIXES

  private fun isNaturalLanguageContext(prefix: String, lineCommentPrefixes: List<String>): Boolean {
    val currentLine = prefix.substringAfterLast('\n').trimStart()
    val lineComment = lineCommentPrefixes.any(currentLine::startsWith)
    val blockComment = BLOCK_COMMENT_DELIMITERS.any { (open, close) ->
      prefix.lastIndexOf(open) > prefix.lastIndexOf(close)
    }
    val documentationString = DOCUMENTATION_DELIMITERS.any { delimiter ->
      prefix.split(delimiter).size % 2 == 0
    }
    return lineComment || blockComment || documentationString
  }

  private fun trimCommentOverflow(completion: String, prefix: String, lineCommentPrefixes: List<String>): String {
    val currentLine = prefix.substringAfterLast('\n').trimStart()
    val lineComment = lineCommentPrefixes.any(currentLine::startsWith)
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

  /**
   * `#` starts a comment in scripting and configuration languages but a selector in CSS, a heading
   * in Markdown, a private field in JavaScript and a directive in C; `--` is a comment in SQL-like
   * languages and a decrement elsewhere. Without a known language the broad set applies.
   */
  internal fun lineCommentPrefixes(languageId: String): List<String> {
    val language = languageId.lowercase()
    // Plain text is usually a format without a language plugin (.conf, .ignore files), so it keeps the broad set.
    if (language.isBlank() || language in UNKNOWN_FORMAT_LANGUAGES) return ALL_LINE_COMMENT_PREFIXES
    return buildList {
      add("//")
      if (language in HASH_COMMENT_LANGUAGE_IDS || HASH_COMMENT_LANGUAGES.any(language::contains)) add("#")
      if (DASH_COMMENT_LANGUAGES.any(language::contains)) add("--")
    }
  }

  private const val MIN_OVERLAP_LENGTH = 2

  /**
   * Only the frame tags the prompt itself uses, plus upper-case variants of the cursor marker that
   * models invent. Matching any tag containing "cursor" also cut real code such as
   * `<button className="cursor-pointer">`, `List<Cursor>` and `start < cursor && end > 0`.
   */
  private val PROMPT_ARTIFACT = Regex(
    "</?(?:CURSOR[A-Z_]*|code_before_cursor|code_after_cursor|cursor_kind|completion_intent|semantic_context)>",
  )
  private val FENCE_LINE = Regex("(?m)^[ \\t]*```[^\\n]*$")
  private val EXPLANATION_LINE = Regex(
    "(?im)^[ \\t]*(?:the cursor\\b|the completion should\\b|wait,|looking at\\b|" +
      "explanation:|here(?:'s| is)\\b|i would\\b|sure[,!:])",
  )
  private val WHITESPACE_RUN = Regex("\\s+")
  private const val PROSE_GAP = "[^=(){};<>]"
  private val INLINE_CODE = Regex("`[^`\\n]+`")
  private val CODE_PUNCTUATION = setOf('(', ')', '{', '}', '[', ']', '=', '<', '>', '$', '|', '&', '\\', '"')
  private val REASONING_WORDS = Regex(
    "(?i)\\b(?:cursor|completion|insert(?:ed|ion)?|intent|likely|unclear|plausible|guess|placeholder|" +
      "i'll|i will|i would|need(?:s|ed)? (?:a|an|the|to)|should (?:be|return|insert))\\b",
  )
  private val NO_INSERTION_EXPLANATIONS = listOf(
    // A placeholder for "nothing", such as "(no completion)" or "[empty]".
    Regex("(?i)^[(\\[<]?\\s*(?:no (?:completion|insertion|suggestion|change)s?|nothing(?: to (?:insert|add|complete))?|empty)\\s*[)\\]>]?\\.?$"),
    // The words between the subject and its verdict must be prose. Allowing any characters also
    // matched code: `document.readyState === 'complete') {` and `List<Nothing> ... items.add(item)`.
    Regex(
      "(?i)^(?:the\\s+)?(?:yaml|yml|json|xml|html|document|file|configuration|config|code|structure)" +
        "(?:\\s+structure)?\\s$PROSE_GAP{0,160}\\b(?:already\\s+)?complete\\b",
    ),
    Regex(
      "(?i)\\bno\\s+(?:additional|further|other)\\s+(?:text|code|content|completion|changes?)\\b" +
        "$PROSE_GAP{0,160}\\b(?:insert(?:ed|ion)?|add(?:ed|ition)?|need(?:ed|s)?|require(?:d|s)?)\\b",
    ),
    Regex(
      "(?i)(?<![<.\\w])(?:nothing|no\\s+insertion)\\s$PROSE_GAP{0,120}\\b(?:insert(?:ed)?|add(?:ed)?|need(?:ed|s)?|require(?:d|s)?)\\b",
    ),
  )
  private val BLOCK_OPENING_SUFFIXES = listOf("{", ":", "=>")
  private val SAFE_SINGLE_CHARACTER_OVERLAPS = setOf(',', ';')
  private val CLOSING_BOUNDARIES = setOf(')', ']', '}', '"', '\'')
  private val ALL_LINE_COMMENT_PREFIXES = listOf("//", "#", "--")
  private val HASH_COMMENT_LANGUAGES = listOf(
    "python", "ruby", "shell", "bash", "zsh", "yaml", "yml", "toml", "perl", "docker", "makefile",
    "properties", "powershell", "elixir", "ini", "terraform", "hcl", "cmake", "gitignore", "dotenv",
  )
  /** Short IDs that would match unrelated languages as substrings. */
  private val HASH_COMMENT_LANGUAGE_IDS = setOf("r", "julia", "nim", "nix", "tcl", "crystal", "coffeescript", "graphql")
  private val DASH_COMMENT_LANGUAGES = listOf("sql", "lua", "haskell", "elm")
  private val UNKNOWN_FORMAT_LANGUAGES = setOf("text", "plain_text", "plaintext", "textmate", "any")
  private val PROSE_LANGUAGES = setOf("markdown", "restructuredtext", "asciidoc")
  private val BLOCK_COMMENT_DELIMITERS = listOf("/*" to "*/", "<!--" to "-->")
  private val DOCUMENTATION_DELIMITERS = listOf("\"\"\"", "'''")
  private val WORD_CONTINUATION_SUFFIXES = setOf(
    "s", "es", "ed", "er", "ers", "est", "ing", "ly", "y", "ies", "ied", "tion", "ation",
    "ions", "ations", "ment", "ments", "ness", "able", "ible", "al", "ally", "ity", "ities",
    "ous", "ive", "ize", "ise",
  )
}
