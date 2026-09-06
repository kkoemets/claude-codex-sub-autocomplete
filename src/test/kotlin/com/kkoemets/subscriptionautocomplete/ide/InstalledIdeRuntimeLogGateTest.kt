package com.kkoemets.subscriptionautocomplete.ide

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstalledIdeRuntimeLogGateTest {
  @Test
  fun `multiline platform SEVERE record with plugin frames fails`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), severe)
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertEquals(1, scan.pluginErrors.size)
    assertEquals(1, scan.pluginErrors.single().line)
    assertFailsWith<IllegalStateException> { scan.requireNoPluginErrors() }
  }

  @Test
  fun `plugin ERROR logger fails while warnings and normal plugin messages do not`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      INFO - #com.kkoemets.subscriptionautocomplete.Plugin - Loaded
      WARN - #com.kkoemets.subscriptionautocomplete.Plugin - Recoverable issue
      ERROR - #com.kkoemets.subscriptionautocomplete.Plugin - Runtime failure
    """.trimIndent())
    assertEquals(1, InstalledIdeRuntimeLogGate.scan(logs).pluginErrors.size)
  }

  @Test
  fun `abbreviated plugin error loggers fail without stacktraces`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      2026-09-06 00:09:52,314 [ 137623] ERROR - #c.k.s.terminal.ClassicTerminalCompletionTarget - Capture failed
      2026-09-06 00:09:52,315 [ 137624] SEVERE - #c.k.s.t.TerminalCompletionService - Dispatch failed
      INFO - #c.k.s.terminal.ClassicTerminalCompletionTarget - Recovered
      WARN - #c.k.s.t.TerminalCompletionService - Retry available
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertEquals(2, scan.pluginErrors.size)
    assertTrue(scan.unrelatedErrors.isEmpty())
    assertFailsWith<IllegalStateException> { scan.requireNoPluginErrors() }
  }

  @Test
  fun `abbreviated plugin names outside the logger field do not claim unrelated errors`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      ERROR - #com.intellij.diagnostic.Logger - Mentioned #c.k.s.terminal.ClassicTerminalCompletionTarget - details
          context: #c.k.s.t.TerminalCompletionService - diagnostic text
      SEVERE - #c.k.something.OtherPlugin - Different package
      INFO - #c.k.s.terminal.ClassicTerminalCompletionTarget - Loaded
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertTrue(scan.pluginErrors.isEmpty())
    assertEquals(2, scan.unrelatedErrors.size)
    scan.requireNoPluginErrors()
  }

  @Test
  fun `unrelated ERROR does not inherit following plugin INFO attribution`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      ERROR - #com.android.tools.idea.assistant.Assistant - Bundle unavailable
          at com.android.tools.idea.assistant.Assistant.load(Assistant.kt:12)
      INFO - #com.kkoemets.subscriptionautocomplete.Plugin - Loaded
    """.trimIndent())
    val messages = mutableListOf<String>()
    assertEquals("finished", InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, messages::add) { "finished" })
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertTrue(scan.pluginErrors.isEmpty())
    assertEquals(1, scan.unrelatedErrors.size)
    assertTrue(messages.any { it.startsWith("Unrelated IDE error:") })
  }

  @Test
  fun `saved stacktrace fails even when current idea log contains no error`() = withLogs { logs ->
    val saved = Files.createDirectories(logs.resolve("errors/error-2"))
    Files.writeString(saved.resolve("stacktrace.txt"), """
      java.lang.IllegalStateException: Must not execute inside read action
          at com.kkoemets.subscriptionautocomplete.terminal.TerminalCompletionService.capture(Service.kt:227)
    """.trimIndent())
    assertEquals(saved.resolve("stacktrace.txt"), InstalledIdeRuntimeLogGate.scan(logs).pluginErrors.single().file)
  }

  @Test
  fun `rotated compressed logs and explicit plugin blame are checked`() = withLogs { logs ->
    GZIPOutputStream(Files.newOutputStream(logs.resolve("idea.log.1.gz"))).bufferedWriter().use {
      it.write("[SEVERE] Plugin to blame: Claude/Codex Sub Autocomplete version: 0.6.4\n")
    }
    assertEquals(1, InstalledIdeRuntimeLogGate.scan(logs).pluginErrors.size)
  }

  @Test
  fun `missing or empty primary logs cannot pass`() = withLogs { logs ->
    assertFailsWith<IllegalStateException> { InstalledIdeRuntimeLogGate.scan(logs.resolve("absent")) }
    Files.delete(logs.resolve("idea.log"))
    assertFailsWith<IllegalStateException> { InstalledIdeRuntimeLogGate.scan(logs) }
    Files.createFile(logs.resolve("idea.log"))
    assertFailsWith<IllegalStateException> { InstalledIdeRuntimeLogGate.scan(logs) }
  }

  @Test
  fun `late shutdown error is checked after an early fixture return`() = withLogs { logs ->
    var shutdownFlushed = false
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) {
        try {
          return@afterIdeShutdown "terminal-only fixture complete"
        } finally {
          Files.writeString(logs.resolve("idea.log"), severe)
          shutdownFlushed = true
        }
      }
    }
    assertTrue(shutdownFlushed)
  }

  @Test
  fun `original fixture failure is preserved with suppressed runtime log failure`() = withLogs { logs ->
    val original = AssertionError("editor acceptance failed")
    val thrown = assertFailsWith<AssertionError> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) {
        try {
          throw original
        } finally {
          Files.writeString(logs.resolve("idea.log"), severe)
        }
      }
    }
    assertSame(original, thrown)
    assertEquals(1, thrown.suppressed.size)
    assertTrue(thrown.suppressed.single().message.orEmpty().contains("Installed plugin runtime errors"))
  }

  @Test
  fun `missing log failure is suppressed rather than masking original startup failure`() = withLogs { logs ->
    val original = IllegalArgumentException("IDE startup failed")
    Files.delete(logs.resolve("idea.log"))
    val thrown = assertFailsWith<IllegalArgumentException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { throw original }
    }
    assertSame(original, thrown)
    assertTrue(thrown.suppressed.single().message.orEmpty().contains("Missing or empty"))
  }

  private fun withLogs(block: (Path) -> Unit) {
    val logs = Files.createTempDirectory("installed-runtime-log-test-")
    try {
      Files.writeString(logs.resolve("idea.log"), "INFO - #com.intellij.idea.Main - IDE started\n")
      block(logs)
    } finally {
      logs.toFile().deleteRecursively()
    }
  }

  private val severe = """
    2026-09-06 00:09:52,314 [ 137623] SEVERE - #c.i.u.c.ThreadingAssertions - Access from Event Dispatch Thread is not allowed
    java.lang.IllegalStateException: EDT violation
        at com.intellij.util.concurrency.ThreadingAssertions.softAssertBackgroundThread(ThreadingAssertions.java:113)
        at com.kkoemets.subscriptionautocomplete.terminal.TerminalCompletionService.capture(Service.kt:227)
    2026-09-06 00:09:53,000 [ 138000] INFO - #com.intellij.idea.Main - Still running
  """.trimIndent()
}
