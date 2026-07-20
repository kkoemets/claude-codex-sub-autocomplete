package com.kkoemets.subscriptionautocomplete.context

internal object AdapterSupport {
  fun matchingLines(text: String, pattern: Regex, limit: Int): String = text
    .lineSequence()
    .filter { pattern.containsMatchIn(it) }
    .take(limit)
    .joinToString("\n")

  fun declarationOutline(text: String, pattern: Regex, limit: Int = 16): String = text
    .lineSequence()
    .map(String::trim)
    .filter { pattern.containsMatchIn(it) }
    .take(limit)
    .joinToString("\n")

  fun currentLine(text: String, offset: Int): String {
    val safe = offset.coerceIn(0, text.length)
    val start = text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf('\n', safe).let { if (it < 0) text.length else it }
    return text.substring(start, end)
  }

  fun enclosingBraceBlock(text: String, offset: Int, maxTokens: Int = 320): String? {
    val safe = offset.coerceIn(0, text.length)
    val openings = ArrayDeque<Int>()
    var quote: Char? = null
    var escaped = false
    for (index in 0 until safe) {
      val char = text[index]
      if (escaped) {
        escaped = false
        continue
      }
      if (char == '\\' && quote != null) {
        escaped = true
        continue
      }
      if (char == '\'' || char == '"' || char == '`') {
        quote = if (quote == char) null else if (quote == null) char else quote
        continue
      }
      if (quote != null) continue
      when (char) {
        '{' -> openings.addLast(index)
        '}' -> if (openings.isNotEmpty()) openings.removeLast()
      }
    }
    val start = openings.lastOrNull() ?: return null
    var depth = 0
    quote = null
    escaped = false
    var end = text.length
    for (index in start until text.length) {
      val char = text[index]
      if (escaped) {
        escaped = false
        continue
      }
      if (char == '\\' && quote != null) {
        escaped = true
        continue
      }
      if (char == '\'' || char == '"' || char == '`') {
        quote = if (quote == char) null else if (quote == null) char else quote
        continue
      }
      if (quote != null) continue
      if (char == '{') depth += 1
      if (char == '}') {
        depth -= 1
        if (depth == 0) {
          end = index + 1
          break
        }
      }
    }
    val block = text.substring(start, end)
    return TextBudget.around(block, safe - start, maxTokens)
  }

  fun enclosingIndentedBlock(text: String, offset: Int, header: Regex, maxTokens: Int = 320): String? {
    val lines = LineView(text)
    val lineNumber = lines.lineNumber(offset)
    val headerIndex = (lineNumber downTo 0).firstOrNull { header.containsMatchIn(lines[it]) } ?: return null
    val headerIndent = lines[headerIndex].takeWhile(Char::isWhitespace).length
    val end = ((headerIndex + 1)..lines.lastIndex).firstOrNull { index ->
      val line = lines[index]
      line.isNotBlank() && line.takeWhile(Char::isWhitespace).length <= headerIndent
    } ?: lines.size
    val block = lines.section(headerIndex, end)
    val localOffset = offset.coerceIn(0, text.length) - lines.startOffset(headerIndex)
    return TextBudget.around(block, localOffset, maxTokens)
  }

  fun currentStatement(text: String, offset: Int, delimiter: Char = ';'): String {
    val safe = offset.coerceIn(0, text.length)
    val start = text.lastIndexOf(delimiter, (safe - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val end = text.indexOf(delimiter, safe).let { if (it < 0) text.length else it + 1 }
    return TextBudget.around(text.substring(start, end), safe - start, 360).trim()
  }

  internal class LineView(private val text: String) {
    private val starts = buildList {
      add(0)
      text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    val size: Int
      get() = starts.size

    val lastIndex: Int
      get() = starts.lastIndex

    operator fun get(index: Int): String {
      val safe = index.coerceIn(starts.indices)
      val start = starts[safe]
      val next = starts.getOrNull(safe + 1) ?: text.length
      val end = if (next > start && text.getOrNull(next - 1) == '\n') next - 1 else next
      return text.substring(start, end)
    }

    fun lineNumber(offset: Int): Int {
      val safe = offset.coerceIn(0, text.length)
      val found = starts.binarySearch(safe)
      return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }

    fun startOffset(line: Int): Int = starts[line.coerceIn(starts.indices)]

    fun section(startLine: Int, endLineExclusive: Int): String {
      val start = startOffset(startLine)
      val end = if (endLineExclusive >= size) text.length else startOffset(endLineExclusive)
      return text.substring(start, end).trimEnd('\n')
    }
  }
}
