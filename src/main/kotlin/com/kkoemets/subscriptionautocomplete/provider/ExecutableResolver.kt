package com.kkoemets.subscriptionautocomplete.provider

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

object ExecutableResolver {
  fun resolve(command: String, configuredPath: String): Path? {
    configuredPath.trim().takeIf(String::isNotEmpty)?.let { configured ->
      val path = Path.of(configured)
      if (isUsable(path)) return path
    }

    val executableNames = if (System.getProperty("os.name").lowercase().contains("win")) {
      listOf("$command.exe", "$command.cmd", "$command.bat", command)
    } else {
      listOf(command)
    }
    val pathCandidates = System.getenv("PATH")
      .orEmpty()
      .split(File.pathSeparator)
      .filter(String::isNotBlank)
      .flatMap { directory -> executableNames.map { Path.of(directory, it) } }
    val userHome = System.getProperty("user.home").orEmpty()
    val commonCandidates = when (command) {
      "codex" -> listOf(
        "/Applications/ChatGPT.app/Contents/Resources/codex",
        "$userHome/.local/bin/codex",
        "/opt/homebrew/bin/codex",
        "/usr/local/bin/codex",
      )
      "claude" -> listOf(
        "$userHome/.local/bin/claude",
        "$userHome/.claude/local/claude",
        "/opt/homebrew/bin/claude",
        "/usr/local/bin/claude",
      )
      else -> emptyList()
    }.map(Path::of)

    return (pathCandidates + commonCandidates).firstOrNull(::isUsable)
  }

  private fun isUsable(path: Path): Boolean = runCatching {
    path.isRegularFile() && (path.isExecutable() || Files.isExecutable(path))
  }.getOrDefault(false)
}
