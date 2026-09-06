package com.kkoemets.subscriptionautocomplete.ide

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.waitFor
import java.nio.file.Path
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The legacy TerminalToolWindowManager always falls back to Classic for REWORKED. */
internal fun Driver.exerciseInstalledReworkedTerminal(application: Path, fixture: TerminalSmokeFixture) {
  val project = singleProject()
  fixture.assertNotExecuted()
  val requestCountBefore = fixture.requestCount()
  val externalInput = System.getProperty("ideTest.externalTerminalInput", "false").toBoolean()
  val inputTimeout = (if (externalInput) 120 else 15).seconds
  val view = withContext(OnDispatcher.EDT) {
    utility(ReworkedTabsRef::class).getInstance(project).createTabBuilder()
      .workingDirectory(project.getBasePath())
      .tabName("Autocomplete Reworked")
      .requestFocus(true)
      .createTab().getView()
  }
  println("Reworked terminal view: $view")
  fun text(): String = view.getOutputModels().getRegular().text()
  waitFor("Reworked shell ready", 30.seconds) { text().isNotBlank() }
  printReworkedInputDiagnostics(view, "shell ready")
  activateTestIde(application)
  ideFrame { robot.focus(view.getPreferredFocusableComponent()) }
  takeScreenshot("compatibility-${getProductVersion().productCode}-reworked-terminal-input-ready")
  if (!externalInput) ideFrame { keyboard { typeText(TERMINAL_FIXTURE_REQUEST) } }
  waitFor("Reworked request rendered", inputTimeout) {
    text().contains(TERMINAL_FIXTURE_REQUEST)
  }
  takeScreenshot("compatibility-${getProductVersion().productCode}-reworked-terminal-requested")
  printReworkedInputDiagnostics(view, "before physical completion Tab")
  if (!externalInput) ideFrame { keyboard { tab() } }
  waitFor("Reworked generated command replaced request", inputTimeout) {
    val rendered = text()
    fixture.commandIsRendered(rendered) &&
      !rendered.contains(TERMINAL_FIXTURE_REQUEST)
  }
  takeScreenshot("compatibility-${getProductVersion().productCode}-reworked-terminal-review")
  assertEquals(requestCountBefore + 1, fixture.requestCount(), "Physical Tab must generate exactly one request")
  proveEditableInputAndExecutionSentinel(fixture, ::text, view::sendText)
  println("Reworked terminal: physical Tab replaced the request; editable command remained unexecuted; explicit Enter created the sentinel")
  exerciseReworkedSearchFocus(view, fixture, externalInput)
  exerciseReworkedProgramTab(view, fixture, externalInput, alternateScreen = false)
  exerciseReworkedProgramTab(view, fixture, externalInput, alternateScreen = true)
}

private fun Driver.printReworkedInputDiagnostics(view: ReworkedViewRef, phase: String) {
  val details = runCatching {
    val pending = view.getShellIntegrationDeferred()
    if (!pending.isCompleted()) return@runCatching "shell integration pending"
    val integration = pending.getCompleted() ?: return@runCatching "shell integration unavailable"
    val block = integration.getBlocksModel().getActiveBlock()
    "status=${integration.getOutputStatus().getValue()}; activeBlock=$block; " +
      "typedInput=${typedReworkedInput(view)}; inputFocused=${view.getPreferredFocusableComponent().isFocusOwner()}"
  }.getOrElse { "diagnostic read failed: ${it.message}" }
  println("Reworked input [$phase]: $details")
}

private fun Driver.typedReworkedInput(view: ReworkedViewRef): String? {
  val pending = view.getShellIntegrationDeferred()
  if (!pending.isCompleted()) return null
  val integration = pending.getCompleted() ?: return null
  val block = integration.getBlocksModel().getActiveBlock() ?: return null
  return utility(TerminalBlockTextRef::class).getTypedCommandText(
    cast(block, TerminalCommandBlockRef::class), view.getOutputModels().getRegular())
}

private fun Driver.assertNoTerminalRequest(fixture: TerminalSmokeFixture, expected: Long, context: String) {
  // running is set synchronously before provider work is queued; the audit file
  // also catches requests that already completed before this assertion.
  assertFalse(service(TerminalCompletionServiceRef::class, singleProject()).isRunning(), "$context queued a terminal request")
  assertEquals(expected, fixture.requestCount(), "$context invoked the provider")
}

private fun Driver.exerciseReworkedSearchFocus(
  view: ReworkedViewRef,
  fixture: TerminalSmokeFixture,
  externalInput: Boolean,
) {
  view.sendText(TERMINAL_FIXTURE_REQUEST)
  waitFor("pending Reworked request before Find", 15.seconds) { typedReworkedInput(view) == TERMINAL_FIXTURE_REQUEST }
  val requestsBefore = fixture.requestCount()
  val acknowledgment = fixture.marker.resolveSibling("${fixture.marker.fileName}.search-tab-ack")
  check(Files.notExists(acknowledgment)) { "Search Tab acknowledgment already exists: $acknowledgment" }
  invokeAction("Terminal.Find", now = true, component = view.getPreferredFocusableComponent())
  var search: TerminalSearchComponentRef? = null
  waitFor("Find search field owns actual keyboard focus", 15.seconds) {
    val focusOwner = utility(KeyboardFocusManagerRef::class).getCurrentKeyboardFocusManager().getFocusOwner()
    val searchAncestor = generateSequence(focusOwner) { it.getParent() }.firstOrNull { component ->
      cast(component.getClass(), RuntimeClassRef::class).getName() == "com.intellij.find.SearchReplaceComponent"
    }
    search = searchAncestor?.let { cast(it, TerminalSearchComponentRef::class) }
    search?.getSearchTextComponent()?.isFocusOwner() == true
  }
  val searchPanel = requireNotNull(search)
  val field = searchPanel.getSearchTextComponent()
  val searchProbe = "autocomplete-search-focus-probe"
  withContext(OnDispatcher.EDT) { field.setText(searchProbe) }
  assertTrue(field.isFocusOwner(), "Search text must own focus before physical Tab")
  printReworkedInputDiagnostics(view, "Find field focused")
  takeScreenshot("compatibility-${getProductVersion().productCode}-reworked-search-tab-ready")
  println("Reworked search: press physical Tab once in the focused Find field; keep the pending terminal request unchanged")
  try {
    if (externalInput) {
      println("Reworked search acknowledgment: after physical Tab and observing its UI effect, create $acknowledgment")
      waitFor("operator acknowledged physical Tab in Find", 120.seconds) { Files.exists(acknowledgment) }
      Files.delete(acknowledgment)
    } else {
      ideFrame { keyboard { tab() } }
    }
    waitFor("physical Tab is handled by Find while the IDE owns focus", 15.seconds) {
      utility(KeyboardFocusManagerRef::class).getCurrentKeyboardFocusManager().getFocusOwner() != null &&
        (!field.isFocusOwner() || field.getText() != searchProbe)
    }
    assertNoTerminalRequest(fixture, requestsBefore, "Tab in Find")
    assertEquals(TERMINAL_FIXTURE_REQUEST, typedReworkedInput(view), "Tab in Find changed terminal input")
    fixture.assertNotExecuted()
    println("Reworked search: Find handled physical Tab; terminal input unchanged; no provider request")
  } finally {
    Files.deleteIfExists(acknowledgment)
    withContext(OnDispatcher.EDT) { searchPanel.close() }
    ideFrame { robot.focus(view.getPreferredFocusableComponent()) }
    view.sendText("\u0015")
    waitFor("pending request cleared after Find", 15.seconds) { typedReworkedInput(view).isNullOrBlank() }
  }
}

private fun OutputModelRef.text(): String {
  val snapshot = takeSnapshot()
  return snapshot.getText(snapshot.getStartOffset(), snapshot.getEndOffset()).toString()
}

/** A real child owns input and prints a request-shaped line; Tab must reach it once. */
private fun Driver.exerciseReworkedProgramTab(
  view: ReworkedViewRef,
  fixture: TerminalSmokeFixture,
  externalInput: Boolean,
  alternateScreen: Boolean,
) {
  val token = UUID.randomUUID().toString()
  val ready = "AUTOCOMPLETE_TAB_READY_$token"
  val done = "AUTOCOMPLETE_TAB_DONE_$token"
  val script = fixture.marker.resolveSibling("terminal-tab-$token.sh")
  val engineState = if (alternateScreen) "alternate-program" else "running-program"
  Files.writeString(script, """
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
  """.trimIndent())
  val requestsBefore = fixture.requestCount()
  var finished = false
  try {
    view.sendText("/bin/bash --noprofile --norc ${shellQuote(script.toString())}\r")
    val model = if (alternateScreen) view.getOutputModels().getAlternative() else view.getOutputModels().getRegular()
    waitFor("Reworked $engineState owns input", 15.seconds) { model.text().contains(ready) }
    ideFrame { robot.focus(view.getPreferredFocusableComponent()) }
    takeScreenshot("compatibility-${getProductVersion().productCode}-reworked-$engineState-tab-ready")
    println("Reworked $engineState: press physical Tab once; the child must receive byte 9 without a provider request")
    if (!externalInput) ideFrame { keyboard { tab() } }
    waitFor("Reworked $engineState received Tab", (if (externalInput) 120 else 15).seconds) {
      view.getOutputModels().getRegular().text().contains("$done:")
    }
    val result = view.getOutputModels().getRegular().text()
    assertTrue(Regex("${Regex.escape(done)}:\\s*9\\s*:END").containsMatchIn(result),
      "A running $engineState must receive exactly one Tab byte; terminal output: $result")
    assertNoTerminalRequest(fixture, requestsBefore, "Tab in $engineState")
    fixture.assertNotExecuted()
    finished = true
    println("Reworked $engineState: exactly one Tab byte reached the child; no provider request")
  } finally {
    if (!finished) view.sendText("\u0003")
    Files.deleteIfExists(script)
  }
}

@Remote("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager", plugin = "org.jetbrains.plugins.terminal/intellij.terminal.frontend")
internal interface ReworkedTabsRef {
  fun getInstance(project: Project): ReworkedTabsRef
  fun createTabBuilder(): ReworkedTabBuilderRef
}

@Remote("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabBuilder", plugin = "org.jetbrains.plugins.terminal/intellij.terminal.frontend")
internal interface ReworkedTabBuilderRef {
  fun workingDirectory(path: String?): ReworkedTabBuilderRef
  fun tabName(name: String): ReworkedTabBuilderRef
  fun requestFocus(focus: Boolean): ReworkedTabBuilderRef
  fun createTab(): ReworkedTabRef
}

@Remote("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab", plugin = "org.jetbrains.plugins.terminal/intellij.terminal.frontend")
internal interface ReworkedTabRef {
  fun getView(): ReworkedViewRef
}

@Remote("com.intellij.terminal.frontend.view.TerminalView", plugin = "org.jetbrains.plugins.terminal/intellij.terminal.frontend")
internal interface ReworkedViewRef {
  fun getOutputModels(): OutputModelsRef
  fun getPreferredFocusableComponent(): Component
  fun sendText(text: String)
  fun getShellIntegrationDeferred(): TerminalIntegrationDeferredRef
}

@Remote("com.kkoemets.subscriptionautocomplete.terminal.TerminalCompletionService", plugin = "com.kkoemets.subscriptionautocomplete")
internal interface TerminalCompletionServiceRef {
  fun isRunning(): Boolean
}

@Remote("java.awt.KeyboardFocusManager")
internal interface KeyboardFocusManagerRef {
  fun getCurrentKeyboardFocusManager(): KeyboardFocusManagerRef
  fun getFocusOwner(): Component?
}

@Remote("java.lang.Class")
internal interface RuntimeClassRef {
  fun getName(): String
}

@Remote("com.intellij.find.SearchReplaceComponent")
internal interface TerminalSearchComponentRef {
  fun getSearchTextComponent(): SearchTextComponentRef
  fun close()
}

@Remote("javax.swing.text.JTextComponent")
internal interface SearchTextComponentRef : Component {
  fun getText(): String
  fun setText(text: String)
}

@Remote("kotlinx.coroutines.Deferred")
internal interface TerminalIntegrationDeferredRef {
  fun isCompleted(): Boolean
  fun getCompleted(): TerminalShellIntegrationRef?
}

@Remote("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalShellIntegrationRef {
  fun getOutputStatus(): TerminalOutputStatusFlowRef
  fun getBlocksModel(): TerminalBlocksModelRef
}

@Remote("kotlinx.coroutines.flow.StateFlow")
internal interface TerminalOutputStatusFlowRef {
  fun getValue(): TerminalOutputStatusRef
}

@Remote("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalOutputStatusRef {
  override fun toString(): String
}

@Remote("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalBlocksModel", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalBlocksModelRef {
  fun getActiveBlock(): TerminalBlockRef?
}

@Remote("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalBlockBase", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalBlockRef {
  override fun toString(): String
}

@Remote("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalCommandBlockRef

@Remote("org.jetbrains.plugins.terminal.view.shellIntegration.TerminalBlocksKt", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalBlockTextRef {
  fun getTypedCommandText(block: TerminalCommandBlockRef, model: OutputModelRef): String?
}

@Remote("org.jetbrains.plugins.terminal.view.TerminalOutputModelsSet", plugin = "org.jetbrains.plugins.terminal")
internal interface OutputModelsRef {
  fun getRegular(): OutputModelRef
  fun getAlternative(): OutputModelRef
}

@Remote("org.jetbrains.plugins.terminal.view.TerminalOutputModel", plugin = "org.jetbrains.plugins.terminal")
internal interface OutputModelRef {
  fun takeSnapshot(): OutputSnapshotRef
}

@Remote("org.jetbrains.plugins.terminal.view.TerminalOutputModelSnapshot", plugin = "org.jetbrains.plugins.terminal")
internal interface OutputSnapshotRef {
  fun getStartOffset(): TerminalOffsetRef
  fun getEndOffset(): TerminalOffsetRef
  fun getText(start: TerminalOffsetRef, end: TerminalOffsetRef): TerminalTextRef
}

@Remote("org.jetbrains.plugins.terminal.view.TerminalOffset", plugin = "org.jetbrains.plugins.terminal")
internal interface TerminalOffsetRef

@Remote("java.lang.CharSequence")
internal interface TerminalTextRef {
  override fun toString(): String
}
