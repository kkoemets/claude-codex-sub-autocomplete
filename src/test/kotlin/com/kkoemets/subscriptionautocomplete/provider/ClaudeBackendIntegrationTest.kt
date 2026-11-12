package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.FakeClaudeFixture.Companion.delta
import com.kkoemets.subscriptionautocomplete.provider.FakeClaudeFixture.Companion.hanging
import com.kkoemets.subscriptionautocomplete.provider.FakeClaudeFixture.Companion.out
import com.kkoemets.subscriptionautocomplete.provider.FakeClaudeFixture.Companion.success
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandPromptBuilder
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandSanitizer
import com.kkoemets.subscriptionautocomplete.terminal.TerminalPromptContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the production Claude backend against a fake CLI through a real launcher on every operating
 * system. On Windows that launcher is a `.cmd` shim, the path that produced
 * "Invalid JSON provided to --settings" before settings moved out of the argument list.
 */
class ClaudeBackendIntegrationTest {
  @Test
  fun `editor requests pin every Claude model and use isolated restricted processes`() = runBlocking {
    for (model in ProviderPolicy.claudeModels) {
      FakeClaudeFixture.create(listOf(success("value * 2"))).use { cli ->
        val result = ClaudeBackend().complete(PROMPT, cli.settings.copy(claudeModel = model))
        assertEquals("value * 2", assertIs<BackendResult.Success>(result).text)
        assertEquals(model, result.model)
        assertEquals(PROMPT.userPrompt, cli.read("input"))
        assertEquals(model, cli.option("--model"))
        assertEquals("low", cli.option("--effort"))
        assertEquals("stream-json", cli.option("--output-format"))
        assertEquals("", cli.option("--tools"))
        assertEquals("*", cli.option("--disallowedTools"))
        assertEquals("dontAsk", cli.option("--permission-mode"))
        listOf("--safe-mode", "--no-session-persistence", "--strict-mcp-config", "--disable-slash-commands")
          .forEach { assertContains(cli.arguments(), it) }
        val settings = JsonParser.parseString(cli.read("settings")).asJsonObject
        assertEquals(model, settings.get("model").asString)
        assertEquals(listOf(model), settings.getAsJsonArray("availableModels").map { it.asString })
        assertTrue(settings.get("enforceAvailableModels").asBoolean)
        assertEquals(0, settings.getAsJsonArray("fallbackModel").size())
        assertEquals("1\n1\n0\n", cli.read("environment"))
        cli.assertWorkspaceRemoved()
        cli.assertProcessesStopped()
      }
    }
  }

  @Test
  fun `settings and system prompt reach the CLI as files so no argument needs quoting`() = runBlocking {
    FakeClaudeFixture.create(listOf(success("ok"))).use { cli ->
      assertIs<BackendResult.Success>(ClaudeBackend().complete(HOSTILE_PROMPT, cli.settings))
      assertEquals(ClaudeBackend.SETTINGS_FILE, cli.option("--settings"))
      assertEquals(ClaudeBackend.SYSTEM_PROMPT_FILE, cli.option("--system-prompt-file"))
      assertFalse("--system-prompt" in cli.arguments(), "The system prompt must not be an argument value")
      // Byte-identical despite quotes, line breaks, cmd.exe metacharacters and non-ASCII text.
      assertEquals(
        HOSTILE_PROMPT.systemPrompt + "\nReturn no more than 32 approximate tokens.",
        cli.read("system-prompt"),
      )
      assertEquals(HOSTILE_PROMPT.userPrompt, cli.read("input"))
      cli.arguments().forEach { argument ->
        assertTrue(WindowsCommandLine.isPortable(argument), "Argument needs launcher-specific quoting: $argument")
      }
    }
  }

  @Test
  fun `an argument error is reported even though the CLI never reads the prompt`() = runBlocking {
    val rejection = "Error: Invalid JSON provided to --settings"
    FakeClaudeFixture.create(emptyList(), rejectArguments = rejection).use { cli ->
      // Larger than any pipe buffer: writing it to a process that already exited breaks the pipe.
      val prompt = PROMPT.copy(userPrompt = "x".repeat(2_000_000))
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(prompt, cli.settings))
      assertEquals(rejection, result.message)
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `an outdated CLI that rejects an option produces update guidance`() = runBlocking {
    FakeClaudeFixture.create(emptyList(), rejectArguments = "error: unknown option '--safe-mode'").use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
      assertContains(result.message, "unknown option '--safe-mode'")
      assertContains(result.message, "claude update")
    }
  }

  @Test
  fun `a model name that is not a plain identifier never reaches a process`() = runBlocking {
    for (model in listOf("haiku\" --dangerously-skip-permissions", "my model", "50%off", "a&b", "x\ny")) {
      FakeClaudeFixture.create(listOf(success("unexpected"))).use { cli ->
        val result = assertIs<BackendResult.Failure>(
          ClaudeBackend().complete(PROMPT, cli.settings.copy(claudeModel = model)),
        )
        assertContains(result.message, "is not valid")
        assertEquals(0, cli.requestCount())
      }
    }
  }

  @Test
  fun `terminal requests travel through the same Claude backend without execution`() = runBlocking {
    FakeClaudeFixture.create(listOf(success("git status --short"))).use { cli ->
      val prompt = TerminalCommandPromptBuilder.build(
        TerminalPromptContext("show short git status", "zsh", "/workspace/sample", "sample", listOf(".git")),
      )
      val result = assertIs<BackendResult.Success>(ClaudeBackend().complete(prompt, cli.settings))
      assertEquals("git status --short", TerminalCommandSanitizer.sanitize(result.text))
      assertEquals(prompt.userPrompt, cli.read("input"))
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `rejected authentication never starts generation`() = runBlocking {
    val statuses = listOf(
      """{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}""" to 1,
      """{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}""" to 0,
      """{"loggedIn":true,"authMethod":"api_key","apiProvider":"firstParty"}""" to 0,
      """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"bedrock"}""" to 0,
      """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"vertex"}""" to 0,
      "not json" to 0,
    )
    for ((status, exitCode) in statuses) {
      FakeClaudeFixture.create(listOf(success("unexpected")), status, exitCode).use { cli ->
        assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
        assertEquals(0, cli.requestCount(), "Generation ran after rejected authentication")
      }
    }
  }

  @Test
  fun `organization restriction suppresses repeated generation attempts`() = runBlocking {
    val denied = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Your organization has disabled Claude subscription access for Claude Code"}"""
    FakeClaudeFixture.create(listOf(out(denied), "exit 1")).use { cli ->
      val backend = ClaudeBackend()
      repeat(2) {
        assertContains(assertIs<BackendResult.Failure>(backend.complete(PROMPT, cli.settings)).message, "administrator")
      }
      assertEquals(1, cli.requestCount())
    }
  }

  @Test
  fun `failed process cannot turn an interrupted stream into a completion`() = runBlocking {
    val scenario = listOf(out(delta("return unfinished(")), "err connection interrupted", "exit 7")
    FakeClaudeFixture.create(scenario).use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
      assertContains(result.message, "connection interrupted")
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `execution errors retain the CLI error detail`() = runBlocking {
    val denied = """{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Subscription usage limit reached"]}"""
    FakeClaudeFixture.create(listOf(out(denied), "exit 1")).use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
      assertContains(result.message, "Subscription usage limit reached")
    }
  }

  @Test
  fun `timeout stops the provider and its child process`() = runBlocking {
    FakeClaudeFixture.create(hanging()).use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings.copy(timeoutSeconds = 4)))
      assertContains(result.message, "timed out")
      cli.assertProcessesStopped()
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `cancellation stops the provider and its child process`() = runBlocking {
    FakeClaudeFixture.create(hanging()).use { cli ->
      val request = async(Dispatchers.IO) { ClaudeBackend().complete(PROMPT, cli.settings) }
      withTimeout(15_000) { while (!cli.exists("child")) delay(20) }
      request.cancelAndJoin()
      cli.assertProcessesStopped()
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `output overflow stops the entire process tree without a partial suggestion`() = runBlocking {
    FakeClaudeFixture.create(hanging(out(delta("x".repeat(300))))).use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings.copy(maxOutputTokens = 8)))
      assertContains(result.message, "output safety envelope")
      cli.assertProcessesStopped()
      cli.assertWorkspaceRemoved()
    }
  }

  private companion object {
    val PROMPT = CompletionPrompt("Return only insertion text. Do not call tools.", "Return twice the value: return <CURSOR>;")

    /** Everything that a Windows launcher rewrites when it appears in an argument value. */
    val HOSTILE_PROMPT = CompletionPrompt(
      systemPrompt = "Return only \"insertion\" text.\nSecond line: 100% & <done> | ^caret 'single' \\\" C:\\dir\\ " +
        "%PATH% !var! \u00fc\u00f1\u00ef \u65e5\u672c\u8a9e \ud83d\ude80",
      userPrompt = "val greeting = \"h\u00e9llo\"\nprintln(<CURSOR>)",
    )
  }
}
