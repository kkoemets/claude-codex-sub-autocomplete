package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonParser
import com.intellij.execution.CommandLineUtil
import com.intellij.execution.Platform
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guards the argument transport that issue #3 broke on Windows
 * ("Error: Invalid JSON provided to --settings").
 */
class ProviderCommandLineTest {
  @Test
  fun `the Windows model splits what the JDK joins`() {
    assertEquals(listOf("a", "b c", "", "d"), WindowsCommandLine.split(WindowsCommandLine.join(listOf("a", "b c", "", "d"))))
    assertEquals(listOf("C:\\dir with space\\"), WindowsCommandLine.split(WindowsCommandLine.join(listOf("C:\\dir with space\\"))))
    assertEquals(listOf("say \"hi\""), WindowsCommandLine.split("\"say \\\"hi\\\"\""))
    assertEquals(listOf("a\\\\b", "c\"d"), WindowsCommandLine.split("a\\\\b c\\\"d"))
  }

  @Test
  fun `inline settings JSON loses its quotes on Windows which is the reported failure`() {
    val inlineSettings = """{"model":"haiku","availableModels":["haiku"],"enforceAvailableModels":true,"fallbackModel":[]}"""
    val received = WindowsCommandLine.split(WindowsCommandLine.join(listOf("--settings", inlineSettings)))

    assertEquals("--settings", received[0])
    assertNotEquals(inlineSettings, received[1])
    assertEquals("{model:haiku,availableModels:[haiku],enforceAvailableModels:true,fallbackModel:[]}", received[1])
  }

  @Test
  fun `every production argument is plain text that no launcher rewrites`() {
    productionCommands().forEach { (name, command) ->
      command.drop(1).forEach { argument ->
        assertTrue(WindowsCommandLine.isPortable(argument), "$name passes an argument that needs quoting: [$argument]")
      }
    }
  }

  @Test
  fun `production commands survive the Windows command line for exe and cmd launchers`() {
    val launchers = listOf(
      "C:\\Users\\Jane Doe\\.local\\bin\\claude.exe",
      "C:\\Users\\Jane Doe\\AppData\\Roaming\\npm\\claude.cmd",
      "C:\\tools\\claude.bat",
    )
    productionCommands().forEach { (name, command) ->
      val arguments = command.drop(1)
      launchers.forEach { launcher ->
        // The same escaping ProcessRunner.start applies, evaluated for Windows on any host.
        val escaped = CommandLineUtil.toCommandLine(launcher, arguments, Platform.WINDOWS)
        val received = WindowsCommandLine.split(WindowsCommandLine.join(escaped.drop(1)))
        assertEquals(arguments, received, "$name arguments changed on the way to $launcher")
        escaped.drop(1).forEach { argument ->
          // cmd.exe metacharacter escaping would mean a shim sees something other than the JDK sent.
          assertFalse(argument.contains('^') || argument.contains('%'), "$name needs cmd.exe escaping: [$argument]")
        }
      }
    }
  }

  @Test
  fun `the Claude command refers to files it has written into the working directory`() {
    val workspace = Files.createTempDirectory("claude-command-")
    try {
      val prompt = CompletionPrompt("Return only \"code\".\nNo prose.", "user text")
      val command = ClaudeBackend().prepareCommand(Path.of("claude"), workspace, prompt, "claude-haiku-5-5", 64)

      fun option(name: String): String = command[command.indexOf(name).also { assertTrue(it >= 0) } + 1]
      val settingsFile = workspace.resolve(option("--settings"))
      val promptFile = workspace.resolve(option("--system-prompt-file"))
      assertFalse(Path.of(option("--settings")).isAbsolute, "An absolute path would carry the temp directory's characters")
      assertFalse(Path.of(option("--system-prompt-file")).isAbsolute)
      val settings = JsonParser.parseString(Files.readString(settingsFile)).asJsonObject
      assertEquals("claude-haiku-5-5", settings.get("model").asString)
      assertTrue(settings.get("enforceAvailableModels").asBoolean)
      assertEquals("Return only \"code\".\nNo prose.\nReturn no more than 64 approximate tokens.", Files.readString(promptFile))
      assertFalse(command.any { it.contains("user text") }, "The user prompt travels on standard input")
    } finally {
      workspace.toFile().deleteRecursively()
    }
  }

  @Test
  fun `model and effort names are restricted to launcher-safe identifiers`() {
    (ProviderPolicy.claudeModels + ProviderPolicy.codexModels + ProviderPolicy.retiredCodexModels +
      listOf("haiku", "opus[1m]", "us.anthropic.claude-haiku-5-5-v1:0", "org/model@2")).forEach { model ->
      assertTrue(ProviderPolicy.isValidModelName(model), model)
      assertTrue(WindowsCommandLine.isPortable(model), model)
    }
    listOf("", " ", "my model", "a\"b", "a'b", "50%", "a&b", "a|b", "a^b", "a<b", "x\ny", "-rf", "é", "a".repeat(121))
      .forEach { model -> assertFalse(ProviderPolicy.isValidModelName(model), model) }
    ProviderPolicy.codexReasoningEfforts.forEach { assertTrue(ProviderPolicy.isValidReasoningEffort(it), it) }
    listOf("", "Low", "low\"", "a b", "x=1", "a".repeat(17)).forEach {
      assertFalse(ProviderPolicy.isValidReasoningEffort(it), it)
    }
  }

  private fun productionCommands(): List<Pair<String, List<String>>> {
    val workspace = Files.createTempDirectory("provider-commands-")
    try {
      val prompt = CompletionPrompt("Return only \"insertion\" text.\nNo prose & no 100% guesses.", "user")
      val claude = ProviderPolicy.claudeModels.map { model ->
        "Claude $model" to ClaudeBackend().prepareCommand(Path.of("claude"), workspace, prompt, model, 512)
      }
      val codex = ProviderPolicy.codexModels.flatMap { model ->
        ProviderPolicy.codexReasoningEfforts.map { effort ->
          "Codex exec $model/$effort" to CodexBackend().oneShotCommand(Path.of("codex"), model, effort)
        }
      }
      val appServer = "Codex app-server" to CodexAppServerProtocol.appServerCommand(Path.of("codex"))
      return claude + codex + appServer
    } finally {
      workspace.toFile().deleteRecursively()
    }
  }
}
