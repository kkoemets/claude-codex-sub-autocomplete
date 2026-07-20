package com.kkoemets.subscriptionautocomplete.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendParserTest {
  @Test
  fun `parses Claude print response`() {
    val result = ClaudeBackend().parseResult("""{"is_error":false,"result":"return value"}""", "haiku")

    assertEquals("return value", assertIs<BackendResult.Success>(result).text)
  }

  @Test
  fun `cleans disabled Claude subscription response`() {
    val result = ClaudeBackend().parseResult(
      """{"is_error":true,"result":"Your organization has disabled Claude subscription access for Claude Code · Use an Anthropic API key instead"}""",
      "haiku",
    )

    assertEquals(
      "Claude subscription access is disabled for this organization. " +
        "Ask its administrator to enable Claude Code subscription access.",
      assertIs<BackendResult.Failure>(result).message,
    )
  }

  @Test
  fun `parses Claude streaming result`() {
    val stdout = """
      {"type":"system","subtype":"init"}
      {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"return "}}}
      {"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"value"}}}
      {"type":"result","subtype":"success","is_error":false,"result":"return value"}
    """.trimIndent()

    val result = ClaudeBackend().parseOutput(stdout, "haiku")

    assertEquals("return value", assertIs<BackendResult.Success>(result).text)
    assertEquals("isolated stream-json", result.transport)
  }

  @Test
  fun `uses Claude text deltas when the final streaming event is absent`() {
    val stdout = """
      {"type":"stream_event","event":{"delta":{"type":"text_delta","text":"return "}}}
      {"type":"stream_event","event":{"delta":{"type":"text_delta","text":"value"}}}
    """.trimIndent()

    val result = ClaudeBackend().parseOutput(stdout, "haiku")

    assertEquals("return value", assertIs<BackendResult.Success>(result).text)
  }

  @Test
  fun `rejects Claude streaming text beyond the safety envelope`() {
    val stdout = """
      {"type":"stream_event","event":{"delta":{"type":"text_delta","text":"return value"}}}
    """.trimIndent()

    val result = ClaudeBackend().parseOutput(stdout, "haiku", maxCharacters = 6)

    assertTrue(assertIs<BackendResult.Failure>(result).message.contains("6 allowed"))
  }

  @Test
  fun `Claude stream limiter accepts the exact boundary and stops only after overflow`() {
    val limiter = ClaudeStreamLimiter(8)

    assertEquals(
      false,
      limiter.observe("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"return "}}}"""),
    )
    assertEquals(
      false,
      limiter.observe("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"v"}}}"""),
    )
    assertFalse(limiter.exceededLimit())
    assertTrue(
      limiter.observe("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"a"}}}"""),
    )
    assertTrue(limiter.exceededLimit())
  }

  @Test
  fun `Claude stream limiter lets a completed process exit normally`() {
    val limiter = ClaudeStreamLimiter(8)

    assertEquals(
      false,
      limiter.observe("""{"type":"result","subtype":"success","result":"return value"}"""),
    )
  }

  @Test
  fun `parses final Codex agent message`() {
    val stdout = """
      {"type":"thread.started","thread_id":"one"}
      {"type":"item.completed","item":{"type":"agent_message","text":"first"}}
      {"type":"item.completed","item":{"type":"agent_message","text":"return value"}}
      {"type":"turn.completed"}
    """.trimIndent()
    val result = CodexBackend().parseJsonLines(stdout, "gpt-5.6-luna")

    assertEquals("return value", assertIs<BackendResult.Success>(result).text)
  }

  @Test
  fun `Codex one shot accepts exact boundary and rejects overflow`() {
    fun stdout(text: String): String =
      """{"type":"item.completed","item":{"type":"agent_message","text":"$text"}}"""

    val exact = CodexBackend().parseJsonLines(stdout("12345678"), "gpt-test", maxCharacters = 8)
    val overflow = CodexBackend().parseJsonLines(stdout("123456789"), "gpt-test", maxCharacters = 8)

    assertEquals("12345678", assertIs<BackendResult.Success>(exact).text)
    assertTrue(assertIs<BackendResult.Failure>(overflow).message.contains("8 allowed"))
  }

  @Test
  fun `billing environments remove API credentials`() {
    val claude = mutableMapOf(
      "ANTHROPIC_API_KEY" to "secret",
      "CLAUDE_CODE_USE_BEDROCK" to "1",
      "CLAUDE_CODE_OAUTH_TOKEN" to "subscription",
    )
    val codex = mutableMapOf(
      "OPENAI_API_KEY" to "secret",
      "AZURE_OPENAI_ENDPOINT" to "https://example.invalid",
      "CODEX_HOME" to "/credentials",
    )

    BillingEnvironment.subscriptionOnlyClaude(claude)
    BillingEnvironment.subscriptionOnlyCodex(codex)

    assertNull(claude["ANTHROPIC_API_KEY"])
    assertNull(claude["CLAUDE_CODE_USE_BEDROCK"])
    assertEquals("subscription", claude["CLAUDE_CODE_OAUTH_TOKEN"])
    assertEquals("1", claude["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"])
    assertEquals("1", claude["DISABLE_AUTOUPDATER"])
    assertEquals("0", claude["MAX_THINKING_TOKENS"])
    assertNull(codex["OPENAI_API_KEY"])
    assertNull(codex["AZURE_OPENAI_ENDPOINT"])
    assertEquals("/credentials", codex["CODEX_HOME"])
  }
}
