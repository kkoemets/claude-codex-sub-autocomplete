package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandPromptBuilder
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandSanitizer
import com.kkoemets.subscriptionautocomplete.terminal.TerminalPromptContext
import java.nio.file.Files
import java.nio.file.Path
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
import org.junit.Assume.assumeTrue

class ClaudeBackendIntegrationTest {
  @Test
  fun `editor requests pin every Claude model and use isolated restricted processes`() = runBlocking {
    for (model in ProviderPolicy.claudeModels) {
      fixture(success("value * 2")).use { cli ->
        val result = ClaudeBackend().complete(PROMPT, cli.settings.copy(claudeModel = model))
        assertEquals("value * 2", assertIs<BackendResult.Success>(result).text)
        assertEquals(model, result.model)
        assertEquals(PROMPT.userPrompt, cli.read("input"))
        val arguments = cli.read("arguments").split('\u0000').dropLast(1)
        fun option(name: String): String = arguments[arguments.indexOf(name).also { assertTrue(it >= 0) } + 1]
        assertEquals(model, option("--model"))
        assertEquals("low", option("--effort"))
        assertEquals("stream-json", option("--output-format"))
        assertEquals("", option("--tools"))
        assertEquals("*", option("--disallowedTools"))
        assertEquals("dontAsk", option("--permission-mode"))
        listOf("--safe-mode", "--no-session-persistence", "--strict-mcp-config", "--disable-slash-commands")
          .forEach { assertContains(arguments, it) }
        assertContains(option("--system-prompt"), PROMPT.systemPrompt)
        val settings = JsonParser.parseString(option("--settings")).asJsonObject
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
  fun `terminal requests travel through the same Claude backend without execution`() = runBlocking {
    fixture(success("git status --short")).use { cli ->
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
      fixture(success("unexpected"), status, exitCode).use { cli ->
        assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
        assertFalse(Files.exists(cli.directory.resolve("calls")), "Generation ran after rejected authentication")
      }
    }
  }

  @Test
  fun `organization restriction suppresses repeated generation attempts`() = runBlocking {
    val denied = """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"Your organization has disabled Claude subscription access for Claude Code"}"""
    fixture(emit(denied) + "\nexit 1").use { cli ->
      val backend = ClaudeBackend()
      repeat(2) {
        assertContains(assertIs<BackendResult.Failure>(backend.complete(PROMPT, cli.settings)).message, "administrator")
      }
      assertEquals(1, cli.read("calls").lines().count { it.isNotBlank() })
    }
  }

  @Test
  fun `failed process cannot turn an interrupted stream into a completion`() = runBlocking {
    fixture(emit(delta("return unfinished(")) + "\nprintf '%s\\n' 'connection interrupted' >&2\nexit 7").use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
      assertContains(result.message, "connection interrupted")
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `execution errors retain the CLI error detail`() = runBlocking {
    val denied = """{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["Subscription usage limit reached"]}"""
    fixture(emit(denied) + "\nexit 1").use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings))
      assertContains(result.message, "Subscription usage limit reached")
    }
  }

  @Test
  fun `timeout stops the provider and its child process`() = runBlocking {
    fixture(hanging()).use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings.copy(timeoutSeconds = 2)))
      assertContains(result.message, "timed out")
      cli.assertProcessesStopped()
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `cancellation stops the provider and its child process`() = runBlocking {
    fixture(hanging()).use { cli ->
      val request = async(Dispatchers.IO) { ClaudeBackend().complete(PROMPT, cli.settings) }
      withTimeout(5_000) { while (!Files.exists(cli.directory.resolve("child"))) delay(20) }
      request.cancelAndJoin()
      cli.assertProcessesStopped()
      cli.assertWorkspaceRemoved()
    }
  }

  @Test
  fun `output overflow stops the entire process tree without a partial suggestion`() = runBlocking {
    fixture(hanging(emit(delta("x".repeat(300))))).use { cli ->
      val result = assertIs<BackendResult.Failure>(ClaudeBackend().complete(PROMPT, cli.settings.copy(maxOutputTokens = 8)))
      assertContains(result.message, "output safety envelope")
      cli.assertProcessesStopped()
      cli.assertWorkspaceRemoved()
    }
  }

  private fun fixture(body: String, auth: String = AUTH, authExit: Int = 0): Fixture {
    assumeTrue("Subprocess fixtures require a POSIX shell", Files.isExecutable(Path.of("/bin/sh")))
    val directory = Files.createTempDirectory("claude-integration-")
    val executable = directory.resolve("claude")
    val script = """
      #!/bin/sh
      if [ "${'$'}1" = "auth" ] && [ "${'$'}2" = "status" ]; then
        ${emit(auth)}
        exit $authExit
      fi
      record_dir=${quote(directory.toString())}
      printf '%s\n' "${'$'}${'$'}" > "${'$'}record_dir/pid"
      printf '%s\n' request >> "${'$'}record_dir/calls"
      pwd > "${'$'}record_dir/cwd"
      printf '%s\0' "${'$'}@" > "${'$'}record_dir/arguments"
      printf '%s\n' "${'$'}CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" "${'$'}DISABLE_AUTOUPDATER" "${'$'}MAX_THINKING_TOKENS" > "${'$'}record_dir/environment"
      cat > "${'$'}record_dir/input"
      $body
    """.trimIndent()
    Files.writeString(executable, script)
    check(executable.toFile().setExecutable(true, true))
    return Fixture(directory, executable)
  }

  private class Fixture(val directory: Path, executable: Path) : AutoCloseable {
    val settings = AutocompleteSettings.SettingsState(claudeExecutable = executable.toString(), timeoutSeconds = 10, maxOutputTokens = 32)
    fun read(name: String): String = Files.readString(directory.resolve(name))
    fun assertWorkspaceRemoved() = assertFalse(Files.exists(Path.of(read("cwd").trim())), "Temporary provider workspace leaked")
    private fun processes(): List<ProcessHandle> = listOf("pid", "child").mapNotNull { name ->
      directory.resolve(name).takeIf(Files::exists)?.let {
        ProcessHandle.of(Files.readString(it).trim().toLong()).orElse(null)
      }
    }
    suspend fun assertProcessesStopped() {
      repeat(50) {
        if (processes().none(ProcessHandle::isAlive)) return
        delay(20)
      }
      assertTrue(processes().none(ProcessHandle::isAlive), "Provider or child process leaked")
    }
    override fun close() {
      processes().asReversed().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
      directory.toFile().deleteRecursively()
    }
  }

  private companion object {
    const val AUTH = """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}"""
    val PROMPT = CompletionPrompt("Return only insertion text. Do not call tools.", "Return twice the value: return <CURSOR>;")
    fun quote(text: String): String = "'" + text.replace("'", "'\"'\"'") + "'"
    fun emit(json: String): String = "printf '%s\\n' ${quote(json)}"
    fun success(text: String): String = emit(JsonObject().apply {
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
    fun hanging(afterStart: String = ""): String = """
      sleep 30 &
      printf '%s\n' "${'$'}!" > "${'$'}record_dir/child"
      $afterStart
      wait
    """.trimIndent()
  }
}
