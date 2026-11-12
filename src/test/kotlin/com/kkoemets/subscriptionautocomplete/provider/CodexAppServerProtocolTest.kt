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

  @Test
  fun `events that arrive before the turn is registered are replayed`() {
    val registry = TurnRegistry()

    // The reader thread sees these while the requesting thread is still parsing the turn/start response.
    assertFalse(registry.append("turn-1", "val x"))
    assertFalse(registry.append("turn-1", " = 1"))
    assertFalse(registry.complete("turn-1", "", "completed", null))
    val accumulator = TurnAccumulator(64)
    registry.register("turn-1", accumulator)

    assertEquals("completed", accumulator.ready.get().status)
    assertTrue(accumulator.ready.get().serverTerminal)
    assertEquals("val x = 1", accumulator.text())
  }

  @Test
  fun `an early failure is delivered instead of timing out`() {
    val registry = TurnRegistry()

    registry.complete("turn-1", "", "failed", "model overloaded")
    val accumulator = TurnAccumulator(64)
    registry.register("turn-1", accumulator)

    assertEquals("failed", accumulator.ready.get().status)
    assertEquals("model overloaded", accumulator.ready.get().error)
  }

  @Test
  fun `early events for turns that never register stay bounded`() {
    val registry = TurnRegistry()

    repeat(1_000) { turn -> repeat(1_000) { registry.append("orphan-$turn", "x") } }
    val accumulator = TurnAccumulator(100_000)
    registry.register("orphan-0", accumulator)
    registry.register("orphan-999", TurnAccumulator(100_000).also { latest ->
      registry.complete("orphan-999", "", "completed", null)
      assertTrue(latest.text().length <= 256)
    })

    assertEquals("", accumulator.text())
  }

  @Test
  fun `forgotten turns do not replay stale early events`() {
    val registry = TurnRegistry()

    registry.append("turn-1", "stale")
    registry.forget("turn-1", ignoreLateEvents = false)
    val accumulator = TurnAccumulator(64)
    registry.register("turn-1", accumulator)

    assertEquals("", accumulator.text())
  }

  @Test
  fun `unexpected item shapes do not throw while extracting the final message`() {
    listOf(
      """{}""",
      """{"items":null}""",
      """{"items":{}}""",
      """{"items":[null,1,"text",{"type":null,"text":"a"},{"type":"agentMessage","text":null}]}""",
    ).forEach { json ->
      assertEquals("", CodexAppServerProtocol.finalAgentText(JsonParser.parseString(json).asJsonObject), json)
    }
  }
}
