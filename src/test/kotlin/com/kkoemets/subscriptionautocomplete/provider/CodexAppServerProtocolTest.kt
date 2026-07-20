package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexAppServerProtocolTest {
  @Test
  fun `starts an isolated read only thread`() {
    val params = CodexAppServerProtocol.threadStart(
      Path.of("/tmp/autocomplete"),
      CompletionPrompt("complete only", "cursor", CompletionMode.AUTOMATIC),
      "gpt-test",
      "low",
      64,
    )

    assertTrue(params.get("ephemeral").asBoolean)
    assertEquals("read-only", params.get("sandbox").asString)
    assertEquals("never", params.get("approvalPolicy").asString)
    assertEquals("complete only", params.get("baseInstructions").asString)
    assertEquals("low", params.getAsJsonObject("config").get("model_reasoning_effort").asString)
    assertEquals(0, params.getAsJsonObject("config").getAsJsonObject("mcp_servers").size())
    assertFalse(
      params.getAsJsonObject("config").getAsJsonObject("features").get("code_mode_host").asBoolean,
    )
    assertTrue(params.get("developerInstructions").asString.contains("at most 64 approximate tokens"))
  }

  @Test
  fun `app server disables agent capabilities that spawn unused tools`() {
    val command = CodexAppServerProtocol.appServerCommand(Path.of("/usr/local/bin/codex"))

    assertTrue(command.windowed(2).contains(listOf("--disable", "code_mode_host")))
    assertTrue(command.windowed(2).contains(listOf("--disable", "plugins")))
    assertTrue(command.windowed(2).contains(listOf("--disable", "multi_agent")))
  }

  @Test
  fun `archive and interrupt requests identify their exact targets`() {
    assertEquals("thread-1", CodexAppServerProtocol.threadArchive("thread-1").get("threadId").asString)
    val interrupt = CodexAppServerProtocol.turnInterrupt("thread-1", "turn-1")
    assertEquals("thread-1", interrupt.get("threadId").asString)
    assertEquals("turn-1", interrupt.get("turnId").asString)
  }

  @Test
  fun `bounded turn ignores late events without recreating state`() {
    val registry = TurnRegistry()
    val accumulator = TurnAccumulator(8)
    registry.register("turn-1", accumulator)

    assertFalse(registry.append("turn-1", "12345678"))
    assertTrue(registry.append("turn-1", "9"))
    assertEquals("output_limit", accumulator.ready.get().status)
    registry.forget("turn-1", ignoreLateEvents = true)

    assertFalse(registry.append("turn-1", "late"))
    assertFalse(registry.complete("turn-1", "late final", "completed", null))
    assertEquals(0, registry.activeCount())
    assertEquals("12345678", accumulator.text())
  }

  @Test
  fun `exact output boundary completes normally`() {
    val accumulator = TurnAccumulator(8)

    assertFalse(accumulator.append("12345678"))
    accumulator.completeServer("12345678", "completed", null)

    assertEquals("completed", accumulator.ready.get().status)
    assertEquals("12345678", accumulator.text())
  }

  @Test
  fun `oversized terminal output is rejected without changing upstream terminal state`() {
    val accumulator = TurnAccumulator(8)

    accumulator.completeServer("123456789", "completed", null)

    assertEquals("output_limit", accumulator.ready.get().status)
    assertTrue(accumulator.ready.get().serverTerminal)
    assertEquals("completed", accumulator.terminal.get().status)
    assertEquals("12345678", accumulator.text())
  }

  @Test
  fun `extracts the final agent message as a delta fallback`() {
    val turn = JsonParser.parseString(
      """{"items":[{"type":"userMessage","text":"ignored"},{"type":"agentMessage","text":"insert me"}]}""",
    ).asJsonObject

    assertEquals("insert me", CodexAppServerProtocol.finalAgentText(turn))
  }
}
