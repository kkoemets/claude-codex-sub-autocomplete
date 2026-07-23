package com.kkoemets.subscriptionautocomplete.eval.terminal

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object TerminalShellSyntaxChecker {
  fun check(shell: String, command: String): TerminalSyntaxOutcome {
    if (command.isBlank() || command.any { it == '\n' || it == '\r' || it.isISOControl() }) {
      return TerminalSyntaxOutcome.REJECTED
    }
    if (!balanced(command)) return TerminalSyntaxOutcome.REJECTED
    val invocation = when (shell.lowercase()) {
      "bash", "sh", "zsh" -> listOf(shell.lowercase(), "-n")
      "fish" -> listOf("fish", "--no-execute")
      else -> return TerminalSyntaxOutcome.SKIPPED
    }
    return runCatching {
      val process = ProcessBuilder(invocation).redirectErrorStream(true).start()
      process.outputStream.use { output ->
        output.write(command.toByteArray(StandardCharsets.UTF_8))
        output.write('\n'.code)
      }
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        TerminalSyntaxOutcome.SKIPPED
      } else if (process.exitValue() == 0) {
        TerminalSyntaxOutcome.PASSED
      } else {
        TerminalSyntaxOutcome.REJECTED
      }
    }.getOrDefault(TerminalSyntaxOutcome.SKIPPED)
  }

  private fun balanced(command: String): Boolean {
    var singleQuoted = false
    var doubleQuoted = false
    var escaped = false
    var parentheses = 0
    var braces = 0
    command.forEach { character ->
      if (escaped) {
        escaped = false
        return@forEach
      }
      if (character == '\\' && !singleQuoted) {
        escaped = true
        return@forEach
      }
      when {
        character == '\'' && !doubleQuoted -> singleQuoted = !singleQuoted
        character == '"' && !singleQuoted -> doubleQuoted = !doubleQuoted
        !singleQuoted && !doubleQuoted && character == '(' -> parentheses += 1
        !singleQuoted && !doubleQuoted && character == ')' -> parentheses -= 1
        !singleQuoted && !doubleQuoted && character == '{' -> braces += 1
        !singleQuoted && !doubleQuoted && character == '}' -> braces -= 1
      }
      if (parentheses < 0 || braces < 0) return false
    }
    return !singleQuoted && !doubleQuoted && !escaped && parentheses == 0 && braces == 0
  }

  private const val TIMEOUT_SECONDS = 2L
}
