package com.kkoemets.subscriptionautocomplete.provider

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** The parts of the host that decide where a provider CLI is installed. */
internal data class HostEnvironment(
  val windows: Boolean,
  val searchPath: String,
  val userHome: String,
  val variables: Map<String, String> = emptyMap(),
) {
  companion object {
    fun current(): HostEnvironment = HostEnvironment(
      windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
      // System.getenv(name) is case-insensitive on Windows, where the variable is spelled "Path".
      searchPath = System.getenv("PATH").orEmpty(),
      userHome = System.getProperty("user.home").orEmpty(),
      variables = listOf("APPDATA", "LOCALAPPDATA").mapNotNull { name ->
        System.getenv(name)?.takeIf(String::isNotBlank)?.let { name to it }
      }.toMap(),
    )
  }
}

object ExecutableResolver {
  fun resolve(command: String, configuredPath: String): Path? =
    resolve(command, configuredPath, HostEnvironment.current())

  internal fun resolve(command: String, configuredPath: String, host: HostEnvironment): Path? {
    configuredCandidates(configuredPath, host).firstOrNull { isUsable(it, host) }?.let { return it }
    val names = launcherNames(command, host)
    return (pathDirectories(host) + commonDirectories(command, host)).asSequence()
      .flatMap { directory -> names.asSequence().mapNotNull { name -> path(directory, name) } }
      .firstOrNull { isUsable(it, host) }
  }

  /**
   * Windows can only start `.exe` files and, through `cmd.exe`, `.cmd`/`.bat` launchers. npm also
   * installs an extensionless POSIX shell script beside `claude.cmd`; starting it fails, so the
   * bare name is never a candidate there.
   */
  private fun launcherNames(command: String, host: HostEnvironment): List<String> =
    if (host.windows) WINDOWS_EXTENSIONS.map { command + it } else listOf(command)

  /** Accepts what users paste: quoted "Copy as path" values, `~`, and Windows paths without an extension. */
  private fun configuredCandidates(configuredPath: String, host: HostEnvironment): List<Path> {
    var configured = configuredPath.trim()
    if (configured.length >= 2 && configured.first() == configured.last() && configured.first() in "\"'") {
      configured = configured.substring(1, configured.length - 1).trim()
    }
    if (configured.isEmpty()) return emptyList()
    if (configured == "~" || configured.startsWith("~/") || configured.startsWith("~\\")) {
      configured = host.userHome + configured.substring(1)
    }
    val exact = path(configured) ?: return emptyList()
    if (!host.windows || WINDOWS_EXTENSIONS.any { configured.endsWith(it, ignoreCase = true) }) return listOf(exact)
    return WINDOWS_EXTENSIONS.mapNotNull { path(configured + it) }
  }

  private fun pathDirectories(host: HostEnvironment): List<String> =
    host.searchPath.split(File.pathSeparator)
      .map { it.trim().removeSurrounding("\"") }
      .filter(String::isNotBlank)

  private fun commonDirectories(command: String, host: HostEnvironment): List<String> {
    val home = host.userHome.takeIf(String::isNotBlank)
    return if (host.windows) {
      listOfNotNull(
        home?.let { "$it\\.local\\bin" },
        host.variables["APPDATA"]?.let { "$it\\npm" },
        host.variables["LOCALAPPDATA"]?.let { "$it\\Microsoft\\WinGet\\Links" },
        host.variables["LOCALAPPDATA"]?.let { "$it\\pnpm" },
        home?.let { "$it\\.bun\\bin" },
        home?.takeIf { command == "claude" }?.let { "$it\\.claude\\local" },
      )
    } else {
      listOfNotNull(
        "/Applications/ChatGPT.app/Contents/Resources".takeIf { command == "codex" },
        home?.let { "$it/.local/bin" },
        home?.takeIf { command == "claude" }?.let { "$it/.claude/local" },
        "/opt/homebrew/bin",
        "/usr/local/bin",
        home?.let { "$it/.npm-global/bin" },
        home?.let { "$it/.bun/bin" },
      )
    }
  }

  private fun path(first: String, vararg more: String): Path? =
    runCatching { Path.of(first, *more) }.getOrNull()

  private fun isUsable(path: Path, host: HostEnvironment): Boolean = runCatching {
    // The executable bit is meaningless on Windows, where the extension decides launchability.
    Files.isRegularFile(path) && (host.windows || Files.isExecutable(path))
  }.getOrDefault(false)

  private val WINDOWS_EXTENSIONS = listOf(".exe", ".cmd", ".bat")
}
