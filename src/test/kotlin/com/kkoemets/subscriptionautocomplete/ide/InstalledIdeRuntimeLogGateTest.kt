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
  fun `every platform error severity fails a successful fixture`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      ERROR - #com.intellij.openapi.editor.colors.EditorColorsManager - Missing color scheme
      SEVERE - #com.android.tools.idea.assistant.Assistant - Bundle unavailable
      FATAL - #com.intellij.platform.Platform - Startup failed
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertTrue(scan.pluginErrors.isEmpty())
    assertEquals(3, scan.unrelatedErrors.size)
    val failure = assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
    assertTrue(failure.message.orEmpty().contains("EditorColorsManager"))
    assertTrue(failure.message.orEmpty().contains("Assistant"))
    assertTrue(failure.message.orEmpty().contains("Startup failed"))
  }

  @Test
  fun `saved platform stacktrace fails even with a clean primary log`() = withLogs { logs ->
    val saved = Files.createDirectories(logs.resolve("errors/platform-error"))
      .resolve("stacktrace.txt")
    Files.writeString(saved, """
      java.lang.IllegalStateException: Platform service unavailable
          at com.intellij.platform.Service.get(Service.java:42)
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertTrue(scan.pluginErrors.isEmpty())
    assertEquals(saved, scan.unrelatedErrors.single().file)
    val failure = assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
    assertTrue(failure.message.orEmpty().contains("$saved:1"))
  }

  @Test
  fun `platform error in compressed rotation blocks a clean current log`() = withLogs { logs ->
    GZIPOutputStream(Files.newOutputStream(logs.resolve("idea.log.1.gz"))).bufferedWriter().use {
      it.write("[FATAL] #com.intellij.platform.Platform - Failed before rotation\n")
    }
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
  }

  @Test
  fun `plugin diagnostic ERROR reported through warning logger fails`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      INFO - #com.intellij.idea.Main - IDE started
      WARN - #c.k.s.d.DiagnosticsLog - [ERROR] Completion request failed
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertEquals(2, scan.pluginErrors.single().line)
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
  }

  @Test
  fun `JVM fatal banner in a crash log fails without a level header`() = withLogs { logs ->
    val crash = Files.createDirectories(logs.resolve("jvm-crash")).resolve("java_error_in_idea_123.log")
    Files.writeString(crash, """
      #
      # A fatal error has been detected by the Java Runtime Environment:
      # SIGSEGV (0xb) at pc=0x0000000000000000, pid=123, tid=456
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertEquals(crash, scan.unrelatedErrors.single().file)
    assertEquals(2, scan.unrelatedErrors.single().line)
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
  }

  @Test
  fun `uncaught stderr exception fails without a level header`() = withLogs { logs ->
    val stderr = logs.resolve("stderr.txt")
    Files.writeString(stderr, """
      INFO - #com.kkoemets.subscriptionautocomplete.Plugin - Loaded
      Exception in thread "main" java.lang.UnsatisfiedLinkError: native library unavailable
          at com.intellij.platform.NativeLoader.load(NativeLoader.java:42)
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertTrue(scan.pluginErrors.isEmpty())
    assertEquals(stderr, scan.unrelatedErrors.single().file)
    assertEquals(2, scan.unrelatedErrors.single().line)
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
  }

  @Test
  fun `warnings and quoted error labels do not fail a clean fixture`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      INFO - #com.kkoemets.subscriptionautocomplete.Plugin - Loaded
      WARN - #c.k.s.d.DiagnosticsLog - A recoverable warning
      WARNING - #com.intellij.platform.Platform - Retry available
      WARN - #com.intellij.diagnostic.Logger - Documentation mentions [ERROR] records
    """.trimIndent())
    assertEquals("finished", InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "finished" })
  }

  @Test
  fun `successful fixture cannot hide a missing primary log behind a rotation`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log.1"), "INFO - #com.intellij.idea.Main - Old startup\n")
    Files.delete(logs.resolve("idea.log"))
    val failure = assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
    assertTrue(failure.message.orEmpty().contains("Missing or empty"))
  }

  @Test
  fun `empty saved stacktrace cannot silently pass`() = withLogs { logs ->
    val saved = Files.createDirectories(logs.resolve("errors/incomplete-error"))
      .resolve("stacktrace.txt")
    Files.writeString(saved, " \n")
    val failure = assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) { "fixture passed" }
    }
    assertTrue(failure.message.orEmpty().contains("Empty saved IDE stacktrace"))
  }

  @Test
  fun `multiline platform SEVERE record with plugin frames fails`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), severe)
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertEquals(1, scan.pluginErrors.size)
    assertEquals(1, scan.pluginErrors.single().line)
    assertFailsWith<IllegalStateException> { scan.requireNoBlockingErrors() }
  }

  @Test
  fun `MacBook dynamic reload classloader error fails a successful fixture`() = withLogs { logs ->
    // Minimized from the supplied 2026-09-06 macOS report after unloading and reinstalling 0.6.4.
    Files.writeString(logs.resolve("idea.log"), """
      2026-09-06 16:47:14,530 [12918834] SEVERE - #c.i.i.p.PluginManager - class com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings cannot be cast to class com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings (com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings is in unnamed module of loader com.intellij.ide.plugins.cl.PluginClassLoader @a74a6fc; com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings is in unnamed module of loader com.intellij.ide.plugins.cl.PluginClassLoader @19035945)
      java.lang.ClassCastException: class com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings cannot be cast to class com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings (com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings is in unnamed module of loader com.intellij.ide.plugins.cl.PluginClassLoader @a74a6fc; com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings is in unnamed module of loader com.intellij.ide.plugins.cl.PluginClassLoader @19035945)
          at com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings${'$'}Companion.getInstance(AutocompleteSettings.kt:165)
          at com.kkoemets.subscriptionautocomplete.terminal.TerminalWidgetTabInstaller.intercept${'$'}lambda${'$'}0(TerminalCompletionStartupActivity.kt:82)
          at com.kkoemets.subscriptionautocomplete.terminal.TerminalTabSequence.dispatch(TerminalCompletionStartupActivity.kt:149)
          at com.kkoemets.subscriptionautocomplete.terminal.TerminalWidgetTabInstaller.intercept(TerminalCompletionStartupActivity.kt:80)
          at com.kkoemets.subscriptionautocomplete.terminal.TerminalWidgetTabInstaller${'$'}install${'$'}dispatcher${'$'}1.dispatch(TerminalCompletionStartupActivity.kt:51)
          at com.intellij.ide.IdeEventQueue.dispatchByCustomDispatchers(IdeEventQueue.kt:659)
      2026-09-06 16:47:14,530 [12918834] SEVERE - #c.i.i.p.PluginManager - IntelliJ IDEA 2026.2.2  Build #IU-262.10315.125
      2026-09-06 16:47:14,533 [12918837] SEVERE - #c.i.i.p.PluginManager - Plugin to blame: Claude/Codex Sub Autocomplete version: 0.6.4
    """.trimIndent())
    val scan = InstalledIdeRuntimeLogGate.scan(logs)
    assertEquals(2, scan.pluginErrors.size)
    val classloaderError = scan.pluginErrors.first()
    assertEquals(1, classloaderError.line)
    assertTrue(classloaderError.text.contains("java.lang.ClassCastException"))
    assertTrue(classloaderError.text.contains("PluginClassLoader @a74a6fc"))
    assertTrue(classloaderError.text.contains("PluginClassLoader @19035945"))
    assertTrue(classloaderError.text.contains("TerminalWidgetTabInstaller"))
    assertTrue(scan.pluginErrors.last().text.contains("Plugin to blame:"))
    assertTrue(scan.unrelatedErrors.single().text.contains("IntelliJ IDEA 2026.2.2"))
    assertFailsWith<IllegalStateException> { scan.requireNoBlockingErrors() }
    var fixtureCompleted = false
    val failure = assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) {
        fixtureCompleted = true
        "fixture passed"
      }
    }
    assertTrue(fixtureCompleted)
    assertTrue(failure.message.orEmpty().contains("2 plugin, 1 unrelated"))
    assertTrue(failure.message.orEmpty().contains("AutocompleteSettings cannot be cast"))
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
    assertFailsWith<IllegalStateException> { scan.requireNoBlockingErrors() }
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
    assertFailsWith<IllegalStateException> { scan.requireNoBlockingErrors() }
  }

  @Test
  fun `unrelated ERROR does not inherit following plugin INFO attribution`() = withLogs { logs ->
    Files.writeString(logs.resolve("idea.log"), """
      ERROR - #com.android.tools.idea.assistant.Assistant - Bundle unavailable
          at com.android.tools.idea.assistant.Assistant.load(Assistant.kt:12)
      INFO - #com.kkoemets.subscriptionautocomplete.Plugin - Loaded
    """.trimIndent())
    val messages = mutableListOf<String>()
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, messages::add) { "finished" }
    }
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
  fun `late platform shutdown error is checked after an early fixture return`() = withLogs { logs ->
    var shutdownFlushed = false
    assertFailsWith<IllegalStateException> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}) {
        try {
          return@afterIdeShutdown "terminal-only fixture complete"
        } finally {
          Files.writeString(logs.resolve("idea.log"), "ERROR - #com.intellij.platform.Platform - Late shutdown failure\n")
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
    assertTrue(thrown.suppressed.single().message.orEmpty().contains("Installed IDE runtime errors"))
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
