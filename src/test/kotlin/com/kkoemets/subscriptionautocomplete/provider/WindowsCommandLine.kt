package com.kkoemets.subscriptionautocomplete.provider

/**
 * A model of how a Windows child process receives the arguments of a `ProcessBuilder` command.
 *
 * Windows passes one command-line string, not an argument array. The JDK builds that string
 * ([join]) and the child's C runtime splits it again ([split]). The two halves do not agree on
 * embedded double quotes, which is what broke inline JSON arguments. Modelling both lets the
 * failure and its fix be asserted on every operating system; `ClaudeBackendIntegrationTest`
 * additionally exercises the real thing when the suite runs on Windows.
 */
internal object WindowsCommandLine {
  /**
   * `java.lang.ProcessImpl.createCommandLine` in its default "legacy" mode, which IntelliJ's
   * runtime uses: an argument is wrapped in quotes only when it is empty or contains a space or
   * tab and is not already quoted. Embedded quotes are copied verbatim.
   */
  fun join(arguments: List<String>): String = arguments.joinToString(" ") { argument ->
    val alreadyQuoted = argument.length >= 2 && argument.startsWith('"') && argument.endsWith('"') &&
      !argument.endsWith("\\\"")
    if (argument.isEmpty() || (!alreadyQuoted && argument.any { it == ' ' || it == '\t' })) {
      "\"" + argument + "\\".repeat(argument.takeLastWhile { it == '\\' }.length) + "\""
    } else {
      argument
    }
  }

  /** The Microsoft C runtime rules (`parse_cmdline`) that turn the string back into `argv`. */
  fun split(commandLine: String): List<String> {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var inArgument = false
    var quoted = false
    var index = 0
    while (index < commandLine.length) {
      val character = commandLine[index]
      when {
        character == '\\' -> {
          var backslashes = 0
          while (index < commandLine.length && commandLine[index] == '\\') {
            backslashes++
            index++
          }
          inArgument = true
          if (index < commandLine.length && commandLine[index] == '"') {
            current.append("\\".repeat(backslashes / 2))
            if (backslashes % 2 == 1) {
              current.append('"')
              index++
            }
          } else {
            current.append("\\".repeat(backslashes))
          }
          continue
        }
        character == '"' -> {
          inArgument = true
          if (quoted && index + 1 < commandLine.length && commandLine[index + 1] == '"') {
            current.append('"')
            index++
          } else {
            quoted = !quoted
          }
        }
        (character == ' ' || character == '\t') && !quoted -> {
          if (inArgument) {
            arguments += current.toString()
            current.setLength(0)
            inArgument = false
          }
        }
        else -> {
          inArgument = true
          current.append(character)
        }
      }
      index++
    }
    if (inArgument) arguments += current.toString()
    return arguments
  }

  /**
   * True when an argument means the same to every launcher without any escaping: no quotes, no
   * whitespace, and none of the characters `cmd.exe` interprets in a `.cmd` shim's `%*`.
   */
  fun isPortable(argument: String): Boolean = argument.all { it in PORTABLE_CHARACTERS }

  private val PORTABLE_CHARACTERS: Set<Char> =
    (('a'..'z') + ('A'..'Z') + ('0'..'9') + "._:/=@[]{}*,+-".toList()).toSet()
}
