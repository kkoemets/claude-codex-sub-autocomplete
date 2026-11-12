package com.kkoemets.subscriptionautocomplete.provider

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assume.assumeFalse

class ExecutableResolverTest {
  private val root: Path = Files.createTempDirectory("resolver test ")

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `Windows prefers the native executable over the npm shim in the same directory`() {
    val directory = directory("bin")
    listOf("claude", "claude.cmd", "claude.exe", "claude.ps1").forEach { file(directory, it) }

    assertEquals(directory.resolve("claude.exe"), ExecutableResolver.resolve("claude", "", windows(directory)))
  }

  @Test
  fun `Windows uses the cmd shim and never the extensionless shell script npm installs beside it`() {
    val npm = directory("npm")
    listOf("claude", "claude.cmd", "claude.ps1").forEach { file(npm, it) }
    val shellOnly = directory("git-bash")
    file(shellOnly, "claude")

    assertEquals(npm.resolve("claude.cmd"), ExecutableResolver.resolve("claude", "", windows(shellOnly, npm)))
    assertNull(ExecutableResolver.resolve("claude", "", windows(shellOnly)))
  }

  @Test
  fun `the first PATH directory wins`() {
    val first = directory("first")
    val second = directory("second")
    file(first, "codex.cmd")
    file(second, "codex.exe")

    assertEquals(first.resolve("codex.cmd"), ExecutableResolver.resolve("codex", "", windows(first, second)))
  }

  @Test
  fun `a configured path pasted with quotes is accepted`() {
    val executable = file(directory("quoted dir"), "claude.exe")

    assertEquals(executable, ExecutableResolver.resolve("claude", "  \"$executable\"  ", windows()))
    assertEquals(executable, ExecutableResolver.resolve("claude", "'$executable'", windows()))
  }

  @Test
  fun `a configured Windows path without an extension finds the launcher`() {
    val directory = directory("configured")
    file(directory, "claude")
    val shim = file(directory, "claude.cmd")

    assertEquals(shim, ExecutableResolver.resolve("claude", directory.resolve("claude").toString(), windows()))
  }

  @Test
  fun `a configured path may start with the home directory shorthand`() {
    val home = directory("home")
    val executable = executable(directory("home/.local/bin"), "claude")

    assertEquals(executable, ExecutableResolver.resolve("claude", "~/.local/bin/claude", posix(home = home)))
  }

  @Test
  fun `an unusable configured path falls back to PATH instead of failing`() {
    val directory = directory("fallback")
    val executable = executable(directory, "claude")

    assertEquals(executable, ExecutableResolver.resolve("claude", "/does/not/exist/claude", posix(directory)))
    // Characters that are illegal in a path on some systems must not throw.
    assertEquals(executable, ExecutableResolver.resolve("claude", "\u0000bad", posix(directory)))
  }

  @Test
  fun `POSIX requires the executable bit and searches the per-user install directory`() {
    // Windows has no executable bit, so a plain file always counts as executable there.
    assumeFalse(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
    val plain = directory("plain")
    file(plain, "claude")
    val home = directory("posix-home")
    val installed = executable(directory("posix-home/.local/bin"), "claude")

    assertEquals(installed, ExecutableResolver.resolve("claude", "", posix(plain, home = home)))
  }

  @Test
  fun `nothing is returned when the command is not installed`() {
    assertNull(ExecutableResolver.resolve("claude", "", HostEnvironment(false, directory("empty").toString(), root.toString())))
    assertNull(ExecutableResolver.resolve("claude", "", HostEnvironment(true, "", "")))
  }

  private fun windows(vararg path: Path) =
    HostEnvironment(windows = true, searchPath = path.joinToString(File.pathSeparator), userHome = root.resolve("no-home").toString())

  private fun posix(vararg path: Path, home: Path = root.resolve("no-home")) =
    HostEnvironment(windows = false, searchPath = path.joinToString(File.pathSeparator), userHome = home.toString())

  private fun directory(name: String): Path = Files.createDirectories(root.resolve(name))

  private fun file(directory: Path, name: String): Path = Files.writeString(directory.resolve(name), "")

  private fun executable(directory: Path, name: String): Path =
    file(directory, name).also { check(it.toFile().setExecutable(true, true)) }
}
