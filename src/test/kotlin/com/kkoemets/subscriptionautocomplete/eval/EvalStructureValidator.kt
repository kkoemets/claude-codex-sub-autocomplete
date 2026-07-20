package com.kkoemets.subscriptionautocomplete.eval

internal object EvalStructureValidator {
  fun outcome(case: EvalCase, candidate: String): EvalSyntaxOutcome {
    if (case.surface !in DELIMITER_LANGUAGES || candidate.isBlank()) return EvalSyntaxOutcome.SKIPPED
    val text = case.input.prefix + candidate + case.input.suffix
    return if (hasBalancedDelimiters(text)) EvalSyntaxOutcome.PASSED else EvalSyntaxOutcome.REJECTED
  }

  private fun hasBalancedDelimiters(text: String): Boolean {
    val stack = ArrayDeque<Char>()
    var quote: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    var index = 0
    while (index < text.length) {
      val current = text[index]
      val next = text.getOrNull(index + 1)
      when {
        lineComment -> if (current == '\n') lineComment = false
        blockComment && current == '*' && next == '/' -> {
          blockComment = false
          index += 1
        }
        blockComment -> Unit
        quote != null && escaped -> escaped = false
        quote != null && current == '\\' -> escaped = true
        quote != null && current == quote -> quote = null
        quote != null -> Unit
        current == '/' && next == '/' -> {
          lineComment = true
          index += 1
        }
        current == '/' && next == '*' -> {
          blockComment = true
          index += 1
        }
        current == '\'' || current == '"' || current == '`' -> quote = current
        current in OPENING_DELIMITERS -> stack.addLast(current)
        current in CLOSING_DELIMITERS -> {
          if (stack.removeLastOrNull() != matchingOpening(current)) return false
        }
      }
      index += 1
    }
    return stack.isEmpty() && quote == null && !blockComment
  }

  private fun matchingOpening(closing: Char): Char = when (closing) {
    ')' -> '('
    ']' -> '['
    '}' -> '{'
    else -> error("Unsupported closing delimiter: $closing")
  }

  private val DELIMITER_LANGUAGES = setOf("typescript", "javascript", "java", "kotlin", "json")
  private val OPENING_DELIMITERS = setOf('(', '[', '{')
  private val CLOSING_DELIMITERS = setOf(')', ']', '}')
}
