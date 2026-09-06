package com.kkoemets.subscriptionautocomplete.ide

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Uses the installed Terminal plugin and a real shell; only the provider is a fixture. */
internal fun Driver.exerciseInstalledTerminal(application: Path, fixture: TerminalSmokeFixture) {
  val project = singleProject()
  val debugLevel = utility(TerminalDebugLevelRef::class).valueOf("DEBUG")
  for (name in listOf("TerminalCompletionService", "TerminalWidgetTabInstaller")) {
    utility(TerminalDebugLoggerRef::class)
      .getInstance("#com.kkoemets.subscriptionautocomplete.terminal.$name").setLevel(debugLevel)
  }
  val externalInput = System.getProperty("ideTest.externalTerminalInput", "false").toBoolean()
  val inputTimeout = (if (externalInput) 120 else 15).seconds
  fixture.assertNotExecuted()
  val requestCountBefore = fixture.requestCount()
  run {
    val engine = "classic"
    ideFrame { toFront() }
    val terminal = withContext(OnDispatcher.EDT) {
      val manager = service(TerminalManagerRef::class, project)
      manager.createNewSession(project.getBasePath(), "Autocomplete classic", listOf("/bin/bash", "--noprofile", "--norc"), true, false)
    }
    println("$engine terminal reference: $terminal")
    try {
      waitFor("terminal shell ready", 30.seconds) {
        terminal.getText().isNotBlank() && !terminal.isCommandRunning()
      }
      activateTestIde(application)
      withContext(OnDispatcher.EDT) { terminal.requestFocus() }
      waitFor("terminal keyboard focus", 10.seconds) { terminal.hasFocus() }
      takeScreenshot("compatibility-${getProductVersion().productCode}-$engine-terminal-input-ready")
      if (!externalInput) ideFrame { keyboard { typeText(TERMINAL_FIXTURE_REQUEST) } }
      waitFor("terminal request rendered", inputTimeout) {
        terminal.getText().contains(TERMINAL_FIXTURE_REQUEST)
      }
      printClassicInputDiagnostics(terminal, fixture)
      takeScreenshot("compatibility-${getProductVersion().productCode}-$engine-terminal-requested")
      if (!externalInput) ideFrame { keyboard { tab() } }
      waitFor("generated command replaced terminal request", inputTimeout) {
        fixture.commandIsRendered(terminal.getText()) &&
          !terminal.getText().contains(TERMINAL_FIXTURE_REQUEST)
      }
      fixture.assertNotExecuted()
      assertEquals(requestCountBefore + 1, fixture.requestCount(), "Physical Tab must generate exactly one request")
      assertFalse(terminal.isCommandRunning(), "Generated terminal command must remain editable")
      takeScreenshot("compatibility-${getProductVersion().productCode}-$engine-terminal-review")
      proveEditableInputAndExecutionSentinel(fixture, terminal::getText) { terminal.getTtyConnector().write(it) }
      println("$engine terminal: physical Tab replaced the request; editable command remained unexecuted; explicit Enter created the sentinel")
      exerciseClassicProgramTab(terminal, fixture, externalInput, alternateScreen = false)
      exerciseClassicProgramTab(terminal, fixture, externalInput, alternateScreen = true)
    } catch (failure: Throwable) {
      printClassicInputDiagnostics(terminal, fixture, "Classic fixture failed")
      printClassicPluginDiagnostics(fixture)
      throw failure
    }
  }
}

private fun Driver.printClassicInputDiagnostics(
  widget: TerminalWidgetRef,
  fixture: TerminalSmokeFixture,
  phase: String = "before physical completion Tab",
) {
  fun quoted(text: String): String = "\"" + text.replace("\\", "\\\\")
    .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t").replace("\"", "\\\"") + "\""
  val details = runCatching {
    withContext(OnDispatcher.EDT) {
      val shell = requireNotNull(utility(ClassicShellTerminalWidgetRef::class).asShellJediTermWidget(widget))
      val terminal = shell.getTerminal()
      val buffer = shell.getTerminalTextBuffer()
      val cursorX = terminal.getCursorX()
      val cursorY = terminal.getCursorY()
      val row = (cursorY - 1).coerceIn(0, (buffer.getHeight() - 1).coerceAtLeast(0))
      "typedShellCommand=${quoted(shell.getTypedShellCommand())}; " +
        "cursor=($cursorX,$cursorY); cursorRow[$row]=${quoted(buffer.getLine(row).getText())}; " +
        "alternateBuffer=${buffer.isUsingAlternateBuffer()}"
    }
  }.getOrElse { "diagnostic read failed: ${it.message}" }
  appendClassicDiagnostic(fixture, "Classic input [$phase]: $details")
  val focus = runCatching {
    withContext(OnDispatcher.EDT) {
      val shell = requireNotNull(utility(ClassicShellTerminalWidgetRef::class).asShellJediTermWidget(widget))
      val panel = shell.getTerminalPanel()
      val owner = utility(KeyboardFocusManagerRef::class).getCurrentKeyboardFocusManager().getFocusOwner()
      val ownerClass = owner?.let { cast(it.getClass(), RuntimeClassRef::class).getName() }
      "outputStreamPresent=${panel.getTerminalOutputStream() != null}; widgetHasFocus=${widget.hasFocus()}; " +
        "shellHasFocus=${shell.hasFocus()}; shellIsFocusOwner=${shell.isFocusOwner()}; " +
        "panelHasFocus=${panel.hasFocus()}; panelIsFocusOwner=${panel.isFocusOwner()}; " +
        "focusOwnerClass=$ownerClass"
    }
  }.getOrElse { "focus diagnostic failed: ${it.message}" }
  appendClassicDiagnostic(fixture, "Classic focus [$phase]: $focus")
  val eligibility = runCatching {
    val completion = service(ClassicTerminalCompletionServiceDiagnosticsRef::class, singleProject())
    withContext(OnDispatcher.EDT) {
      "canRequest=${completion.canRequest(widget)}; serviceRunning=${completion.isRunning()}"
    }
  }.getOrElse { "eligibility diagnostic failed: ${it.message}" }
  appendClassicDiagnostic(fixture, "Classic eligibility [$phase]: $eligibility")
}

private fun Driver.printClassicPluginDiagnostics(fixture: TerminalSmokeFixture) {
  runCatching {
    val entries = service(ClassicPluginDiagnosticsLogRef::class).snapshot()
    appendClassicDiagnostic(fixture, "Classic failure plugin DiagnosticsLog: ${entries.size} entries; showing last 50")
    entries.takeLast(50).forEach { appendClassicDiagnostic(fixture, "Classic plugin diagnostic: $it") }
  }.onFailure { appendClassicDiagnostic(fixture, "Classic plugin DiagnosticsLog snapshot failed: ${it.message}") }
}

private fun appendClassicDiagnostic(fixture: TerminalSmokeFixture, line: String) {
  println(line)
  runCatching {
    Files.writeString(fixture.marker.resolveSibling("${fixture.marker.fileName}.classic-input-diagnostic"), "$line\n",
      StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }.onFailure { println("Could not persist Classic diagnostic: ${it.message}") }
}

/** A real Classic child owns input; request-shaped output must not intercept Tab. */
private fun Driver.exerciseClassicProgramTab(
  terminal: TerminalWidgetRef,
  fixture: TerminalSmokeFixture,
  externalInput: Boolean,
  alternateScreen: Boolean,
) {
  val token = UUID.randomUUID().toString()
  val ready = "AUTOCOMPLETE_TAB_READY_$token"
  val done = "AUTOCOMPLETE_TAB_DONE_$token"
  val script = fixture.marker.resolveSibling("terminal-classic-tab-$token.sh")
  val engineState = if (alternateScreen) "alternate-program" else "running-program"
  Files.writeString(script, terminalProgramTabScript(ready, done, alternateScreen))
  val requestsBefore = fixture.requestCount()
  var finished = false
  try {
    waitFor("Classic shell idle before $engineState", 15.seconds) { !terminal.isCommandRunning() }
    terminal.getTtyConnector().write("/bin/bash --noprofile --norc ${shellQuote(script.toString())}\r")
    waitFor("Classic $engineState owns input", 15.seconds) {
      val text = terminal.getText()
      text.contains(ready) && text.contains(TERMINAL_FIXTURE_REQUEST) && terminal.isCommandRunning()
    }
    ideFrame { toFront() }
    withContext(OnDispatcher.EDT) { terminal.requestFocus() }
    waitFor("Classic $engineState keyboard focus", 10.seconds) { terminal.hasFocus() }
    takeScreenshot("compatibility-${getProductVersion().productCode}-classic-$engineState-tab-ready")
    println("Classic $engineState: press physical Tab once; the child must receive byte 9 without a provider request")
    if (!externalInput) ideFrame { keyboard { tab() } }
    waitFor("Classic $engineState received Tab", (if (externalInput) 120 else 15).seconds) {
      terminal.getText().contains("$done:")
    }
    val result = terminal.getText()
    assertTrue(Regex("${Regex.escape(done)}:\\s*9\\s*:END").containsMatchIn(result),
      "A running Classic $engineState must receive exactly one Tab byte; terminal output: $result")
    assertFalse(service(TerminalCompletionServiceRef::class, singleProject()).isRunning(),
      "Tab in Classic $engineState queued a terminal request")
    assertEquals(requestsBefore, fixture.requestCount(), "Tab in Classic $engineState invoked the provider")
    fixture.assertNotExecuted()
    finished = true
    println("Classic $engineState: exactly one Tab byte reached the child; no provider request")
  } finally {
    if (!finished) terminal.getTtyConnector().write("\u0003")
    Files.deleteIfExists(script)
  }
}

/** Shared child fixture script for terminal engines; the result exposes every input byte. */
internal fun terminalProgramTabScript(ready: String, done: String, alternateScreen: Boolean): String = """
    #!/bin/bash
    set -eu
    saved_state=${'$'}(stty -g)
    restore_terminal() {
      stty "${'$'}saved_state"
      ${if (alternateScreen) "printf '\\033[?1049l'" else ":"}
    }
    trap restore_terminal EXIT
    stty -echo -icanon min 1 time 0
    ${if (alternateScreen) "printf '\\033[?1049h\\033[H'" else ":"}
    printf '\n%s\n%s' '$ready' '$TERMINAL_FIXTURE_REQUEST'
    first=${'$'}(dd bs=1 count=1 2>/dev/null | od -An -tu1)
    stty min 0 time 10
    rest=${'$'}(dd bs=1 count=64 2>/dev/null | od -An -tu1)
    restore_terminal
    trap - EXIT
    printf '\n%s:%s %s:END\n' '$done' "${'$'}first" "${'$'}rest"
  """.trimIndent()

internal const val TERMINAL_FIXTURE_REQUEST = "# autocomplete terminal compatibility check"

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

internal class TerminalSmokeFixture(projectPath: Path) {
  val marker: Path = projectPath.toAbsolutePath().resolve(".autocomplete-terminal-executed-${UUID.randomUUID()}")
  val requestLog: Path = marker.resolveSibling("${marker.fileName}.requests")
  val command: String = "touch ${shellQuote(marker.toString())}"

  fun assertNotExecuted() {
    check(Files.notExists(marker)) { "Generated terminal command executed without explicit Enter: $marker" }
  }

  fun commandIsRendered(text: String): Boolean =
    text.replace("\r", "").replace("\n", "").contains(command)

  fun requestCount(): Long = if (Files.exists(requestLog)) Files.lines(requestLog).use { it.count() } else 0
}

/** Clearing the command distinguishes editable input from an executed command in scrollback. */
internal fun Driver.proveEditableInputAndExecutionSentinel(
  fixture: TerminalSmokeFixture,
  renderedText: () -> String,
  sendText: (String) -> Unit,
) {
  fixture.assertNotExecuted()
  sendText("\u0015")
  waitFor("generated command was editable input, not terminal history", 15.seconds) {
    !fixture.commandIsRendered(renderedText())
  }
  fixture.assertNotExecuted()
  sendText(fixture.command)
  waitFor("restored generated command remains editable", 15.seconds) {
    fixture.commandIsRendered(renderedText())
  }
  fixture.assertNotExecuted()
  // This deliberate Enter is the positive control for the same absolute marker.
  sendText("\r")
  waitFor("explicit Enter creates the execution sentinel", 15.seconds) { Files.exists(fixture.marker) }
  Files.delete(fixture.marker)
}

internal fun activateTestIde(application: Path) {
  if (!System.getProperty("os.name").startsWith("Mac")) return
  val bundle = generateSequence(application) { it.parent }.first { it.toString().endsWith(".app") }
  val activation = ProcessBuilder("/usr/bin/open", "-a", bundle.toString()).inheritIO().start()
  check(activation.waitFor() == 0) { "Could not activate the running test IDE" }
}

@Remote("org.jetbrains.plugins.terminal.TerminalToolWindowManager", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalManagerRef {
  fun createNewSession(directory: String?, tabName: String?, command: List<String>, requestFocus: Boolean, deferStart: Boolean): TerminalWidgetRef
}

@Remote("com.intellij.terminal.ui.TerminalWidget")
internal interface TerminalWidgetRef {
  fun getText(): String
  fun isCommandRunning(): Boolean
  fun hasFocus(): Boolean
  fun requestFocus()
  fun getTtyConnector(): TtyConnectorRef
}

@Remote("com.intellij.openapi.diagnostic.Logger")
internal interface TerminalDebugLoggerRef {
  fun getInstance(category: String): TerminalDebugLoggerRef
  fun setLevel(level: TerminalDebugLevelRef)
}

@Remote("com.intellij.openapi.diagnostic.LogLevel")
internal interface TerminalDebugLevelRef {
  fun valueOf(level: String): TerminalDebugLevelRef
}

@Remote("org.jetbrains.plugins.terminal.ShellTerminalWidget", plugin = "org.jetbrains.plugins.terminal")
internal interface ClassicShellTerminalWidgetRef {
  fun asShellJediTermWidget(widget: TerminalWidgetRef): ClassicShellTerminalWidgetRef?
  fun getTypedShellCommand(): String
  fun getTerminal(): ClassicTerminalCursorRef
  fun getTerminalTextBuffer(): ClassicTerminalTextBufferRef
  fun getTerminalPanel(): ClassicTerminalPanelRef
  fun hasFocus(): Boolean
  fun isFocusOwner(): Boolean
}

@Remote("com.jediterm.terminal.ui.TerminalPanel")
internal interface ClassicTerminalPanelRef {
  fun getTerminalOutputStream(): ClassicTerminalOutputStreamRef?
  fun hasFocus(): Boolean
  fun isFocusOwner(): Boolean
}

@Remote("com.jediterm.terminal.TerminalOutputStream")
internal interface ClassicTerminalOutputStreamRef

@Remote("com.kkoemets.subscriptionautocomplete.terminal.TerminalCompletionService", plugin = "com.kkoemets.subscriptionautocomplete")
internal interface ClassicTerminalCompletionServiceDiagnosticsRef {
  fun canRequest(widget: TerminalWidgetRef): Boolean
  fun isRunning(): Boolean
}

@Remote("com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog", plugin = "com.kkoemets.subscriptionautocomplete")
internal interface ClassicPluginDiagnosticsLogRef {
  fun snapshot(): List<ClassicPluginDiagnosticEntryRef>
}

@Remote("com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticEntry", plugin = "com.kkoemets.subscriptionautocomplete")
internal interface ClassicPluginDiagnosticEntryRef {
  override fun toString(): String
}

@Remote("com.jediterm.terminal.Terminal")
internal interface ClassicTerminalCursorRef {
  fun getCursorX(): Int
  fun getCursorY(): Int
}

@Remote("com.jediterm.terminal.model.TerminalTextBuffer")
internal interface ClassicTerminalTextBufferRef {
  fun getHeight(): Int
  fun isUsingAlternateBuffer(): Boolean
  fun getLine(index: Int): ClassicTerminalLineRef
}

@Remote("com.jediterm.terminal.model.TerminalLine")
internal interface ClassicTerminalLineRef {
  fun getText(): String
}

@Remote("com.jediterm.terminal.TtyConnector")
internal interface TtyConnectorRef {
  fun write(text: String)
}
