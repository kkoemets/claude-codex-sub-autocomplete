package com.kkoemets.subscriptionautocomplete.context

object LanguageAdapterRegistry {
  private val adapters: List<LanguageContextAdapter> = listOf(
    JvmAdapter,
    JavaScriptAdapter,
    PythonAdapter,
    DockerfileAdapter,
    ShellAdapter,
    YamlAdapter,
    SqlAdapter,
    JsonAdapter,
    MarkupAdapter,
    CssAdapter,
    KeyValueAdapter,
    MarkdownAdapter,
    GenericAdapter,
  )

  fun collect(input: ContextInput): List<ContextFragment> {
    val matching = adapters.dropLast(1).filter { it.supports(input) }
    return (matching.ifEmpty { listOf(GenericAdapter) }).flatMap { it.collect(input) }
  }
}

private fun fragment(
  label: String,
  content: String?,
  priority: Int,
  maxTokens: Int,
  source: String,
): ContextFragment? = content
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?.let { ContextFragment(label, it, priority, maxTokens, source) }

object JvmAdapter : LanguageContextAdapter {
  private val extensions = setOf("java", "kt", "kts", "scala", "sc", "groovy")
  private val declaration = Regex(
    "(?:class|interface|enum|record|object|trait|fun|void|public|private|protected|internal|def)\\b",
  )

  override fun supports(input: ContextInput): Boolean = input.extension in extensions

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Enclosing JVM declaration",
      AdapterSupport.enclosingBraceBlock(input.text, input.caretOffset),
      92,
      320,
      "jvm",
    ),
    fragment(
      "Package and imports",
      AdapterSupport.matchingLines(
        input.text,
        Regex("^\\s*(?:package|import)\\s+"),
        18,
      ),
      72,
      180,
      "jvm",
    ),
    fragment(
      "Nearby declarations",
      AdapterSupport.declarationOutline(input.text, declaration),
      46,
      180,
      "jvm",
    ),
  )
}

object JavaScriptAdapter : LanguageContextAdapter {
  private val extensions = setOf("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue", "svelte")

  override fun supports(input: ContextInput): Boolean = input.extension in extensions

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Enclosing JavaScript or TypeScript block",
      AdapterSupport.enclosingBraceBlock(input.text, input.caretOffset),
      92,
      340,
      "javascript",
    ),
    fragment(
      "Imports and exports",
      AdapterSupport.matchingLines(
        input.text,
        Regex("^\\s*(?:import|export)\\b|\\brequire\\s*\\("),
        20,
      ),
      76,
      200,
      "javascript",
    ),
    fragment(
      "Types and declarations",
      AdapterSupport.declarationOutline(
        input.text,
        Regex("^(?:export\\s+)?(?:async\\s+)?(?:function|class|interface|type|enum|const|let|var)\\b"),
      ),
      55,
      220,
      "javascript",
    ),
  )
}

object PythonAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("py", "pyi", "pyw")

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Enclosing Python declaration",
      AdapterSupport.enclosingIndentedBlock(
        input.text,
        input.caretOffset,
        Regex("^\\s*(?:async\\s+def|def|class)\\s+"),
      ),
      94,
      340,
      "python",
    ),
    fragment(
      "Python imports",
      AdapterSupport.matchingLines(input.text, Regex("^\\s*(?:from\\s+.+\\s+import|import)\\s+"), 18),
      74,
      180,
      "python",
    ),
    fragment(
      "Python declarations",
      AdapterSupport.declarationOutline(input.text, Regex("^(?:async\\s+def|def|class)\\s+")),
      50,
      180,
      "python",
    ),
  )
}

object DockerfileAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean {
    val fileName = input.fileName.lowercase()
    return input.extension == "dockerfile" || fileName == "dockerfile" || fileName.startsWith("dockerfile.")
  }

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Current Dockerfile stage",
      currentStage(input),
      96,
      380,
      "dockerfile",
    ),
    fragment(
      "Dockerfile stages and variables",
      AdapterSupport.matchingLines(
        input.text,
        Regex("(?i)^\\s*(?:FROM|ARG|ENV)\\b"),
        24,
      ),
      66,
      220,
      "dockerfile",
    ),
  )

  private fun currentStage(input: ContextInput): String {
    val lines = AdapterSupport.LineView(input.text)
    val lineNumber = lines.lineNumber(input.caretOffset)
    val start = (lineNumber downTo 0).firstOrNull { FROM_INSTRUCTION.containsMatchIn(lines[it]) } ?: 0
    val end = ((lineNumber + 1)..lines.lastIndex).firstOrNull { FROM_INSTRUCTION.containsMatchIn(lines[it]) }
      ?: lines.size
    val stage = lines.section(start, end)
    val relativeOffset = input.caretOffset.coerceIn(0, input.text.length) - lines.startOffset(start)
    return TextBudget.around(stage, relativeOffset, 380)
  }

  private val FROM_INSTRUCTION = Regex("(?i)^\\s*FROM\\b")
}

object ShellAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("sh", "bash", "zsh", "fish")

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Enclosing shell function",
      AdapterSupport.enclosingBraceBlock(input.text, input.caretOffset),
      92,
      300,
      "shell",
    ),
    fragment(
      "Sourced scripts",
      AdapterSupport.matchingLines(input.text, Regex("^\\s*(?:source|\\.)\\s+"), 12),
      72,
      140,
      "shell",
    ),
    fragment(
      "Shell variables and functions",
      AdapterSupport.declarationOutline(
        input.text,
        Regex("^(?:function\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*(?:\\(\\)\\s*\\{|=)"),
      ),
      55,
      180,
      "shell",
    ),
  )
}

object YamlAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("yaml", "yml")

  override fun collect(input: ContextInput): List<ContextFragment> {
    val compose = input.fileName.lowercase() in setOf(
      "compose.yml",
      "compose.yaml",
      "docker-compose.yml",
      "docker-compose.yaml",
    )
    return listOfNotNull(
      fragment(
        if (compose) "Current Docker Compose section" else "Current YAML section",
        currentYamlSection(input),
        94,
        360,
        "yaml",
      ),
      fragment(
        if (compose) "Compose services and resources" else "YAML structure",
        yamlOutline(input.text, compose),
        62,
        220,
        "yaml",
      ),
      fragment(
        "YAML anchors and references",
        AdapterSupport.matchingLines(input.text, Regex("(?:&|\\*)[A-Za-z0-9_.-]+|<<\\s*:"), 12),
        54,
        140,
        "yaml",
      ),
    )
  }

  private fun currentYamlSection(input: ContextInput): String {
    val lines = AdapterSupport.LineView(input.text)
    val lineNumber = lines.lineNumber(input.caretOffset)
    val currentIndent = lines[lineNumber].takeWhile(Char::isWhitespace).length
    val parent = (lineNumber downTo 0).firstOrNull { index ->
      val line = lines[index]
      val indent = line.takeWhile(Char::isWhitespace).length
      line.trim().let { it.isNotBlank() && !it.startsWith("#") && it.endsWith(":") } &&
        (indent < currentIndent || index == lineNumber)
    } ?: lineNumber
    val parentIndent = lines[parent].takeWhile(Char::isWhitespace).length
    val end = ((parent + 1)..lines.lastIndex).firstOrNull { index ->
      val line = lines[index]
      line.isNotBlank() && !line.trimStart().startsWith("#") &&
        line.takeWhile(Char::isWhitespace).length <= parentIndent
    } ?: lines.size
    val section = lines.section(parent, end)
    val relativeOffset = input.caretOffset.coerceIn(0, input.text.length) - lines.startOffset(parent)
    return TextBudget.around(section, relativeOffset, 360)
  }

  private fun yamlOutline(text: String, compose: Boolean): String {
    val pattern = if (compose) {
      Regex("^(?:services|networks|volumes|configs|secrets):\\s*$|^  [A-Za-z0-9_.-]+:\\s*$")
    } else {
      Regex("^\\S[^#]*:\\s*$|^  [A-Za-z0-9_.-]+:\\s*$")
    }
    return AdapterSupport.matchingLines(text, pattern, 24)
  }
}

object SqlAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("sql", "ddl", "dml")

  override fun collect(input: ContextInput): List<ContextFragment> {
    val statement = AdapterSupport.currentStatement(input.text, input.caretOffset)
    val referencedNames = Regex(
      "(?i)\\b(?:from|join|update|into|table)\\s+([A-Za-z_][A-Za-z0-9_.$]*)",
    ).findAll(statement).map { it.groupValues[1].substringAfterLast('.') }.toSet()
    val tablePattern = referencedNames.joinToString("|") { Regex.escape(it) }
    val definitions = tablePattern.takeIf(String::isNotBlank)?.let { names ->
      Regex(
        "(?is)create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?" +
          "(?:[A-Za-z0-9_]+\\.)?(?:$names)\\b.*?;",
      ).findAll(input.text).map { it.value }
      .take(3)
      .joinToString("\n\n")
    }.orEmpty()
    return listOfNotNull(
      fragment("Current SQL statement", statement, 96, 380, "sql"),
      fragment("Referenced table definitions", definitions, 74, 260, "sql"),
      fragment(
        "SQL declarations",
        AdapterSupport.declarationOutline(
          input.text,
          Regex("(?i)^(?:create|alter)\\s+(?:table|view|function|procedure|type|index)\\b"),
        ),
        48,
        180,
        "sql",
      ),
    )
  }
}

object JsonAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("json", "json5", "jsonc", "geojson")

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Current JSON object",
      AdapterSupport.enclosingBraceBlock(input.text, input.caretOffset, 360),
      95,
      360,
      "json",
    ),
    fragment(
      "JSON keys",
      jsonKeys(input.text),
      52,
      180,
      "json",
    ),
  )

  private fun jsonKeys(text: String): String = Regex("[\"']([A-Za-z0-9_.$@/-]+)[\"']\\s*:")
    .findAll(text)
    .map { it.groupValues[1] }
    .distinct()
    .take(24)
    .joinToString("\n")
}

object MarkupAdapter : LanguageContextAdapter {
  private val extensions = setOf("html", "htm", "xhtml", "xml", "xsd", "svg", "vue", "svelte")

  override fun supports(input: ContextInput): Boolean = input.extension in extensions

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment("Element ancestry", elementAncestry(input), 92, 120, "markup"),
    fragment(
      "IDs, classes, and template declarations",
      AdapterSupport.matchingLines(
        input.text,
        Regex("\\b(?:id|class|name|slot|v-for|v-if|ng-|\\*ng|#|ref)=|<template\\b"),
        16,
      ),
      58,
      220,
      "markup",
    ),
  )

  private fun elementAncestry(input: ContextInput): String {
    val caret = input.caretOffset.coerceIn(0, input.text.length)
    val stack = ArrayDeque<String>()
    for (match in COMPLETE_TAG.findAll(input.text)) {
      if (match.range.last >= caret) break
      val closing = match.groupValues[1].isNotEmpty()
      val name = match.groupValues[2]
      val selfClosing = match.groupValues[3].trimEnd().endsWith("/") || name.lowercase() in VOID_TAGS
      when {
        closing -> {
          val index = stack.indexOfLast { it == name }
          if (index >= 0) while (stack.size > index) stack.removeLast()
        }
        !selfClosing -> stack.addLast(name)
      }
    }
    val partialStart = (caret - PARTIAL_TAG_LOOKBACK).coerceAtLeast(0)
    val partialTag = PARTIAL_TAG.find(input.text.substring(partialStart, caret))?.groupValues?.get(1)
    val completeTags = stack.toList()
    val tags = if (partialTag != null && completeTags.lastOrNull() != partialTag) completeTags + partialTag else completeTags
    return tags.takeLast(10).joinToString(" > ")
  }

  private val COMPLETE_TAG = Regex("<\\s*(/)?\\s*([A-Za-z][A-Za-z0-9:.-]*)([^>]*)>")
  private val PARTIAL_TAG = Regex("<\\s*([A-Za-z][A-Za-z0-9:.-]*)[^<>]*$")
  private const val PARTIAL_TAG_LOOKBACK = 2_048
  private val VOID_TAGS = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source")
}

object CssAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("css", "scss", "sass", "less", "styl")

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment(
      "Current style rule",
      AdapterSupport.enclosingBraceBlock(input.text, input.caretOffset, 320),
      94,
      320,
      "css",
    ),
    fragment(
      "Style variables and mixins",
      AdapterSupport.matchingLines(
        input.text,
        Regex("^\\s*(?:--[A-Za-z0-9_-]+|[$@][A-Za-z0-9_-]+)\\s*:|^\\s*@(?:mixin|function|keyframes)\\b"),
        18,
      ),
      62,
      180,
      "css",
    ),
  )
}

object KeyValueAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf(
    "toml",
    "properties",
    "ini",
    "env",
    "conf",
    "cfg",
  ) || input.fileName.startsWith(".env")

  override fun collect(input: ContextInput): List<ContextFragment> = listOfNotNull(
    fragment("Current configuration section", currentSection(input), 92, 320, "config"),
    fragment(
      "Configuration keys",
      AdapterSupport.matchingLines(input.text, Regex("^\\s*[A-Za-z_][A-Za-z0-9_.-]*\\s*[=:]"), 24),
      52,
      180,
      "config",
    ),
  )

  private fun currentSection(input: ContextInput): String {
    val lines = AdapterSupport.LineView(input.text)
    val lineNumber = lines.lineNumber(input.caretOffset)
    val start = (lineNumber downTo 0).firstOrNull { lines[it].trim().matches(Regex("\\[[^]]+].*")) } ?: 0
    val end = ((start + 1)..lines.lastIndex).firstOrNull { lines[it].trim().matches(Regex("\\[[^]]+].*")) }
      ?: lines.size
    val section = lines.section(start, end)
    val relativeOffset = input.caretOffset.coerceIn(0, input.text.length) - lines.startOffset(start)
    return TextBudget.around(section, relativeOffset, 320)
  }
}

object MarkdownAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = input.extension in setOf("md", "mdx", "markdown")

  override fun collect(input: ContextInput): List<ContextFragment> {
    val lines = AdapterSupport.LineView(input.text)
    val lineNumber = lines.lineNumber(input.caretOffset)
    val start = (lineNumber downTo 0).firstOrNull { lines[it].startsWith("#") } ?: 0
    val level = lines[start].takeWhile { it == '#' }.length.coerceAtLeast(1)
    val end = ((start + 1)..lines.lastIndex).firstOrNull { index ->
      val heading = lines[index].takeWhile { it == '#' }.length
      heading in 1..level
    } ?: lines.size
    val section = lines.section(start, end)
    val relativeOffset = input.caretOffset.coerceIn(0, input.text.length) - lines.startOffset(start)
    return listOfNotNull(
      fragment(
        "Current Markdown section",
        TextBudget.around(section, relativeOffset, 360),
        90,
        360,
        "markdown",
      ),
    )
  }
}

object GenericAdapter : LanguageContextAdapter {
  override fun supports(input: ContextInput): Boolean = true

  override fun collect(input: ContextInput): List<ContextFragment> = emptyList()
}
