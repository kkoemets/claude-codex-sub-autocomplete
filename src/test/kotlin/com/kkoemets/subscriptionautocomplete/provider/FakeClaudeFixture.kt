package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonObject
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

/**
 * A fake Claude Code installation: a real launcher script that starts [FakeProviderCli].
 *
 * On Windows the launcher is a `claude.cmd` shaped like the shim npm installs, so the plugin's
 * arguments pass through `cmd.exe` and the C runtime exactly as they do for a real installation.
 * The directory name contains a space because user profile paths frequently do.
 */
internal class FakeClaudeFixture private constructor(val directory: Path, val executable: Path) : AutoCloseable {
  val settings = AutocompleteSettings.SettingsState(
    claudeExecutable = executable.toString(),
    timeoutSeconds = 20,
    maxOutputTokens = 32,
  )

  fun read(name: String): String = Files.readString(directory.resolve(name))

  fun exists(name: String): Boolean = Files.exists(directory.resolve(name))

  fun arguments(): List<String> = read("arguments").split('\u0000').dropLast(1)

  fun option(name: String): String {
    val arguments = arguments()
    val index = arguments.indexOf(name)
    assertTrue(index >= 0 && index + 1 < arguments.size, "Missing option $name in $arguments")
    return arguments[index + 1]
  }

  fun requestCount(): Int = if (exists("calls")) read("calls").lines().count(String::isNotBlank) else 0

  fun assertWorkspaceRemoved() =
    assertFalse(Files.exists(Path.of(read("cwd").trim())), "Temporary provider workspace leaked")

  private fun processes(): List<ProcessHandle> = listOf("pid", "child").mapNotNull { name ->
    directory.resolve(name).takeIf(Files::exists)?.let {
      ProcessHandle.of(Files.readString(it).trim().toLong()).orElse(null)
    }
  }

  suspend fun assertProcessesStopped() {
    repeat(250) {
      if (processes().none(ProcessHandle::isAlive)) return
      delay(20)
    }
    assertTrue(processes().none(ProcessHandle::isAlive), "Provider or child process leaked")
  }

  override fun close() {
    processes().asReversed().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
    directory.toFile().deleteRecursively()
  }

  companion object {
    const val SIGNED_IN = """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}"""
    val WINDOWS: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /** [scenario] lists what the fake prints and does after it has read its input; see [FakeProviderCli]. */
    fun create(
      scenario: List<String>,
      auth: String = SIGNED_IN,
      authExit: Int = 0,
      rejectArguments: String? = null,
    ): FakeClaudeFixture {
      val directory = Files.createTempDirectory("claude integration ")
      Files.writeString(directory.resolve("scenario"), scenario.joinToString("\n"))
      Files.writeString(directory.resolve("auth.json"), auth)
      Files.writeString(directory.resolve("auth-exit"), authExit.toString())
      rejectArguments?.let { Files.writeString(directory.resolve("reject-arguments"), it) }
      return FakeClaudeFixture(directory, launcher(directory))
    }

    private fun launcher(directory: Path): Path {
      val java = Path.of(System.getProperty("java.home"), "bin", if (WINDOWS) "java.exe" else "java")
      val main = FakeProviderCli::class.java.name
      // The IDE test class loader reports no code source, so locate the class file itself.
      val classFile = Path.of(
        requireNotNull(FakeProviderCli::class.java.getResource("${FakeProviderCli::class.java.simpleName}.class")) {
          "FakeProviderCli is not compiled to a directory"
        }.toURI(),
      )
      val classes = generateSequence(classFile) { it.parent }.elementAt(main.count { it == '.' } + 1)
      return if (WINDOWS) {
        directory.resolve("claude.cmd").also {
          Files.writeString(it, "@echo off\r\n\"$java\" -cp \"$classes\" $main \"$directory\" %*\r\n")
        }
      } else {
        directory.resolve("claude").also {
          Files.writeString(
            it,
            "#!/bin/sh\nexec ${quote(java.toString())} -cp ${quote(classes.toString())} $main " +
              "${quote(directory.toString())} \"\$@\"\n",
          )
          check(it.toFile().setExecutable(true, true))
        }
      }
    }

    private fun quote(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"

    fun out(json: String): String = "out $json"

    fun success(text: String): String = out(JsonObject().apply {
      addProperty("type", "result")
      addProperty("subtype", "success")
      addProperty("is_error", false)
      addProperty("result", text)
    }.toString())

    fun delta(text: String): String = JsonObject().apply {
      addProperty("type", "stream_event")
      add("event", JsonObject().apply {
        add("delta", JsonObject().apply { addProperty("type", "text_delta"); addProperty("text", text) })
      })
    }.toString()

    /** Starts a child that inherits the output pipes, optionally prints, then never exits on its own. */
    fun hanging(vararg afterStart: String): List<String> = listOf("child") + afterStart + "wait"
  }
}
