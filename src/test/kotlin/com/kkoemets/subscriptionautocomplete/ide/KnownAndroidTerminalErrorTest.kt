package com.kkoemets.subscriptionautocomplete.ide

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KnownAndroidTerminalErrorTest {
  private val trace = requireNotNull(javaClass.getResource("/ide/android-terminal-lock.txt")).readText().trimEnd()
  private val message = trace.substringBefore("\n\n").substringAfter(": ")
  private val build = KnownAndroidTerminalError.BUILD

  @Test
  fun `both reproduced stock and candidate variants are accepted and reported`() {
    for (action in listOf("", "InsertInlineCompletionAction")) withIncident(action) { logs ->
      val reports = mutableListOf<String>()
      assertEquals("passed", InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, reports::add, build) { "passed" })
      val scan = InstalledIdeRuntimeLogGate.scan(logs, build)
      assertTrue(scan.pluginErrors.isEmpty() && scan.unrelatedErrors.isEmpty())
      assertEquals(6, scan.acceptedPlatformErrors.size)
      assertEquals(6, reports.count { it.startsWith("Accepted stock Android terminal error:") })
      replace(logs.resolve("idea.log"), "\$Proxy231", "\$Proxy237")
      replace(logs.resolve("errors/error-1/stacktrace.txt"), "\$Proxy231", "\$Proxy999")
      InstalledIdeRuntimeLogGate.scan(logs, build).requireNoBlockingErrors()
    }
  }

  @Test
  fun `no caller supplied build or a different build keeps the strict policy`() = withIncident { logs ->
    for (target in listOf(null, "IU-261.26222.65.2614.16204760", "$build.1")) {
      assertFailsWith<IllegalStateException> { InstalledIdeRuntimeLogGate.scan(logs, target).requireNoBlockingErrors() }
    }
  }

  @Test
  fun `runtime identity cannot be supplied by error metadata alone`() {
    for (replacement in listOf("IDE: Missing", "IDE: Android Studio (build #AI-261.1, date)",
      "IDE: IntelliJ IDEA (build #IU-261.26222.65.2614.16204760, date)")) {
      withIncident { logs ->
        val log = logs.resolve("idea.log")
        replace(log, "IDE: Android Studio (build #$build, date)", replacement)
        assertRejected(logs)
      }
    }
  }

  @Test
  fun `changed missing extra and reordered stack frames remain blocking`() {
    val frame = "\tat com.intellij.openapi.application.ActionsKt.runReadAction(actions.kt:28)"
    val variants = listOf(
      trace.replace("actions.kt:28", "actions.kt:29"),
      trace.replace("$frame\n", ""),
      "$trace\n\tat com.intellij.platform.Unknown.run(Unknown.kt:1)",
      "$frame\n$trace",
    )
    for (changed in variants) withIncident { logs ->
      replace(logs.resolve("idea.log"), trace, changed)
      assertRejected(logs)
    }
  }

  @Test
  fun `additional cause suppressed exception or continuation cannot hide behind known frames`() {
    for (extra in listOf("Caused by: java.lang.IllegalStateException: another failure",
      "Suppressed: java.lang.IllegalStateException: another failure", "Unexpected continuation")) {
      withIncident { logs ->
        replace(logs.resolve("idea.log"), trace, "$trace\n$extra")
        assertRejected(logs)
      }
      withIncident { logs ->
        replace(logs.resolve("errors/error-1/stacktrace.txt"), trace, "$trace\n$extra")
        assertRejected(logs)
      }
    }
  }

  @Test
  fun `plugin attribution in any matched record remains blocking`() {
    val edits = listOf(
      Triple("idea.log", trace, "$trace\n\tat com.kkoemets.subscriptionautocomplete.Plugin.run(Plugin.kt:1)"),
      Triple("idea.log", "#c.i.u.EventDispatcher", "#c.k.s.Plugin"),
      Triple("idea.log", "Last Action:", "Plugin to blame: Claude/Codex Sub Autocomplete"),
      Triple("errors/error-1/message.txt", message, "$message\nPlugin to blame: Claude/Codex Sub Autocomplete"),
    )
    for ((file, old, new) in edits) withIncident { logs ->
      replace(logs.resolve(file), old, new)
      assertTrue(InstalledIdeRuntimeLogGate.scan(logs, build).pluginErrors.isNotEmpty())
      assertRejected(logs)
    }
  }

  @Test
  fun `missing mismatched and extra saved traces remain blocking`() {
    for (file in listOf("stacktrace.txt", "message.txt")) withIncident { logs ->
      Files.delete(logs.resolve("errors/error-1/$file"))
      assertRejected(logs)
    }
    withIncident { logs ->
      replace(logs.resolve("errors/error-1/stacktrace.txt"), "actions.kt:28", "actions.kt:29")
      assertRejected(logs)
    }
    withIncident { logs ->
      val duplicate = Files.createDirectories(logs.resolve("errors/error-2"))
      for (file in listOf("stacktrace.txt", "message.txt")) Files.copy(logs.resolve("errors/error-1/$file"), duplicate.resolve(file))
      assertRejected(logs)
    }
  }

  @Test
  fun `missing changed reordered or separated metadata never receives a standalone exception`() {
    for (change in listOf<(String) -> String>(
      { it.replace("OS: Mac OS X", "OS: Linux") },
      { it.replace("JDK: 25.0.3", "JDK: 25.0.4") },
      { it.replace("Last Action:", "Last Action: UnknownAction") },
      { text -> text.lineSequence().filterNot { it.contains("OS: Mac OS X") }.joinToString("\n") },
      { it.replace("OS: Mac OS X", "TEMP_OS").replace("Last Action: ", "OS: Mac OS X").replace("TEMP_OS", "Last Action:") },
      { it.replace("\n" + header(103), "\nINFO - #com.intellij.platform.Platform - Intervening record\n" + header(103)) },
      { it.replace(header(104), header(300)) },
    )) withIncident { logs ->
      val log = logs.resolve("idea.log")
      Files.writeString(log, change(Files.readString(log)))
      assertRejected(logs)
    }
    withIncident { logs ->
      val log = logs.resolve("idea.log")
      replace(log, header(100) + message + "\n\n" + trace + "\n", "")
      assertRejected(logs)
    }
  }

  @Test
  fun `other platform plugin freeze and shutdown errors still block`() {
    for (error in listOf("ERROR - #com.intellij.platform.Platform - Unknown failure",
      "SEVERE - #c.k.s.Plugin - Plugin failed", "ERROR - #com.intellij.diagnostic.Freeze - UI freeze",
      "FATAL - #com.intellij.platform.Shutdown - Shutdown failed")) withIncident { logs ->
      val log = logs.resolve("idea.log")
      Files.writeString(log, Files.readString(log) + error + "\n")
      assertEquals(6, InstalledIdeRuntimeLogGate.scan(logs, build).acceptedPlatformErrors.size)
      assertRejected(logs)
    }
  }

  @Test
  fun `known exception copied into another file is not accepted`() = withIncident { logs ->
    Files.copy(logs.resolve("idea.log"), logs.resolve("other.log"))
    assertRejected(logs)
  }

  @Test
  fun `accepted platform incident cannot mask a failed functional fixture`() = withIncident { logs ->
    val failure = AssertionError("restart failed")
    assertSame(failure, assertFailsWith<AssertionError> {
      InstalledIdeRuntimeLogGate.afterIdeShutdown(logs, {}, build) { throw failure }
    })
  }

  private fun assertRejected(logs: Path) {
    assertFailsWith<IllegalStateException> { InstalledIdeRuntimeLogGate.scan(logs, build).requireNoBlockingErrors() }
  }

  private fun replace(file: Path, old: String, new: String) {
    val text = Files.readString(file)
    check(old in text) { "Test mutation did not match: $file" }
    Files.writeString(file, text.replace(old, new))
  }

  private fun header(tick: Int) = "2026-09-06 18:43:00,081 [ $tick] SEVERE - #c.i.u.EventDispatcher - "

  private fun withIncident(action: String = "", block: (Path) -> Unit) {
    val logs = Files.createTempDirectory("known-android-error-test-")
    try {
      Files.writeString(logs.resolve("idea.log"), buildString {
        appendLine("2026-09-06 18:41:15,974 [ 97] INFO - #c.i.p.i.b.AppStarter - IDE: Android Studio (build #$build, date)")
        appendLine(header(100) + message + "\n\n" + trace)
        appendLine(header(101) + "Android Studio Quail 4 | 2026.1.4  Build #$build")
        appendLine(header(102) + "JDK: 25.0.3; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o.")
        appendLine(header(103) + "OS: Mac OS X")
        appendLine(header(104) + "Last Action: $action")
        appendLine("INFO - #com.intellij.idea.Main - Finished")
      })
      val saved = Files.createDirectories(logs.resolve("errors/error-1"))
      Files.writeString(saved.resolve("stacktrace.txt"), "$trace\n")
      Files.writeString(saved.resolve("message.txt"), "$message\n")
      block(logs)
    } finally {
      logs.toFile().deleteRecursively()
    }
  }
}
