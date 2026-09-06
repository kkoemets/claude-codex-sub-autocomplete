package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exercise the actual service from EDT; fake terminal methods enforce the SDK thread contracts. */
class TerminalCompletionThreadingTest : BasePlatformTestCase() {
  private lateinit var originalSettings: AutocompleteSettings.SettingsState
  private lateinit var scope: CoroutineScope
  private lateinit var service: TerminalCompletionService

  override fun runInDispatchThread(): Boolean = false

  override fun setUp() {
    super.setUp()
    originalSettings = AutocompleteSettings.getInstance().snapshot()
    AutocompleteSettings.getInstance().update {
      it.enabled = true
      it.terminalCompletionsEnabled = true
      it.provider = ProviderKind.CODEX.name
    }
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    service = TerminalCompletionService(project, scope)
  }

  override fun tearDown() {
    try {
      scope.cancel()
      runBlocking { awaitFinished() }
      AutocompleteSettings.getInstance().loadState(originalSettings)
    } finally { super.tearDown() }
  }

  fun testBothProcessProbesAndContextRunOutsideEdtAndReadAccess() = runBlocking {
    val terminal = StrictTerminal()
    val backend = FixedBackend()
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    awaitFinished()
    assertEquals(2, terminal.probes.get())
    assertEquals(1, terminal.contexts.get())
    assertEquals(1, backend.calls.get())
    assertEquals(listOf("\u0015pwd"), terminal.sent)
    assertEquals(0, terminal.listeners.get())
  }

  fun testBusyCandidateForwardsEachProvisionalTabExactlyOnce() = runBlocking {
    val probe = CountDownLatch(1)
    val terminal = StrictTerminal { check(probe.await(5, TimeUnit.SECONDS)); true }
    val backend = FixedBackend()
    try {
      withContext(Dispatchers.EDT) {
        assertTrue(service.request(terminal, true) { backend })
        assertTrue(service.request(terminal, true) { backend })
      }
    } finally { probe.countDown() }
    awaitFinished()
    assertEquals(listOf("\t\t"), terminal.sent)
    assertEquals(0, backend.calls.get())
    assertEquals(0, terminal.listeners.get())
  }

  fun testLaterKeyFlushesProvisionalTabBeforeNativeInputAndCancelsCandidate() = runBlocking {
    val probe = CountDownLatch(1)
    val terminal = StrictTerminal { check(probe.await(5, TimeUnit.SECONDS)); false }
    val backend = FixedBackend()
    try {
      withContext(Dispatchers.EDT) {
        assertTrue(service.request(terminal, true) { backend })
        service.inputChanged()
        terminal.sendText("later native key")
      }
    } finally { probe.countDown() }
    awaitFinished()
    assertEquals(listOf("\t", "later native key"), terminal.sent)
    assertEquals(0, backend.calls.get())
  }

  fun testBusyOutputRedrawDoesNotSwallowNativeTab() = runBlocking {
    val probe = CountDownLatch(1)
    val terminal = StrictTerminal { check(probe.await(5, TimeUnit.SECONDS)); true }
    val backend = FixedBackend()
    try {
      withContext(Dispatchers.EDT) {
        assertTrue(service.request(terminal, true) { backend })
        terminal.modelRevision++
      }
    } finally { probe.countDown() }
    awaitFinished()
    assertEquals(listOf("\t"), terminal.sent)
    assertEquals(0, backend.calls.get())
  }

  fun testResolvedBusyProbeDoesNotConsumeAnotherTabBeforeCleanup() = runBlocking {
    val terminal = StrictTerminal { true }
    val backend = FixedBackend()
    val checked = CompletableDeferred<Unit>()
    terminal.onSend = {
      assertFalse(service.request(terminal, true) { backend })
      checked.complete(Unit)
    }
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, true) { backend }) }
    withTimeout(5_000) { checked.await() }
    awaitFinished()
    assertEquals(listOf("\t"), terminal.sent)
    assertEquals(0, backend.calls.get())
  }

  fun testManualActionInBusyTerminalDoesNotSendTab() = runBlocking {
    val terminal = StrictTerminal { true }
    val backend = FixedBackend()
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    awaitFinished()
    assertEmpty(terminal.sent)
    assertEquals(0, backend.calls.get())
  }

  fun testRepeatedTabDuringGenerationDoesNotStartAnotherProviderOrForwardTab() = runBlocking {
    val terminal = StrictTerminal()
    val backend = FixedBackend(hold = true)
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, true) { backend }) }
    withTimeout(5_000) { backend.entered.await() }
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, true) { backend }) }
    backend.release.complete(Unit)
    awaitFinished()
    assertEquals(1, backend.calls.get())
    assertEquals(listOf("\u0015pwd"), terminal.sent)
  }

  fun testChangedThenRestoredInputStillRejectsLateProviderResponse() = runBlocking {
    val terminal = StrictTerminal()
    val backend = FixedBackend(hold = true)
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    withTimeout(5_000) { backend.entered.await() }
    withContext(Dispatchers.EDT) { terminal.revision++ }
    backend.release.complete(Unit)
    awaitFinished()
    assertEmpty(terminal.sent)
  }

  fun testRepeatedTabUsesOriginalListenerRevisionAcrossTargetInstances() = runBlocking {
    val terminal = StrictTerminal().also { it.modelRevision = 5 }
    val backend = FixedBackend(hold = true)
    val nextTarget = object : TerminalCompletionTarget by terminal {
      override val identity: Any get() = terminal
      override fun captureInput() = terminal.captureInput()?.copy(modelRevision = 0)
    }
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, true) { backend }) }
    withTimeout(5_000) { backend.entered.await() }
    withContext(Dispatchers.EDT) { assertTrue(service.request(nextTarget, true) { backend }) }
    backend.release.complete(Unit)
    awaitFinished()
    assertEquals(listOf("\u0015pwd"), terminal.sent)
    assertEquals(1, backend.calls.get())
  }

  fun testProgramStartingDuringGenerationRejectsLateResponse() = runBlocking {
    val probes = AtomicInteger()
    val terminal = StrictTerminal { probes.incrementAndGet() > 1 }
    val backend = FixedBackend()
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    awaitFinished()
    assertEquals(2, terminal.probes.get())
    assertEmpty(terminal.sent)
  }

  fun testFocusLossBeforeInsertionRejectsResponse() = runBlocking {
    val terminal = StrictTerminal()
    val backend = FixedBackend(hold = true)
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    withTimeout(5_000) { backend.entered.await() }
    withContext(Dispatchers.EDT) { terminal.focused = false }
    backend.release.complete(Unit)
    awaitFinished()
    assertEmpty(terminal.sent)
  }

  fun testSettingsChangeBeforeInsertionRejectsResponse() = runBlocking {
    val terminal = StrictTerminal()
    val backend = FixedBackend(hold = true)
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    withTimeout(5_000) { backend.entered.await() }
    AutocompleteSettings.getInstance().update { it.terminalCompletionsEnabled = false }
    backend.release.complete(Unit)
    awaitFinished()
    assertEmpty(terminal.sent)
  }

  fun testCancelledScopeDetachesListenerEvenBeforeCoroutineStarts() = runBlocking {
    scope.cancel()
    val terminal = StrictTerminal()
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal)) }
    awaitFinished()
    assertEquals(0, terminal.listeners.get())
    assertEquals(0, terminal.probes.get())
  }

  private suspend fun awaitFinished() = withTimeout(5_000) {
    while (service.isRunning()) delay(5)
  }

  private class StrictTerminal(private val busy: () -> Boolean = { false }) : TerminalCompletionTarget {
    val probes = AtomicInteger()
    val contexts = AtomicInteger()
    val listeners = AtomicInteger()
    val sent = mutableListOf<String>()
    var revision = 0L
    var modelRevision = 0L
    var focused = true
    var onSend: () -> Unit = {}
    override fun captureInput(): TerminalCommandInput? {
      check(ApplicationManager.getApplication().isDispatchThread)
      return TerminalCommandInput("# print working directory", "print working directory", revision, modelRevision).takeIf { focused }
    }
    override fun watchChanges(): AutoCloseable {
      check(ApplicationManager.getApplication().isDispatchThread)
      listeners.incrementAndGet()
      return AutoCloseable { listeners.decrementAndGet() }
    }
    override fun isCommandRunning(): Boolean {
      assertBackgroundAccess()
      probes.incrementAndGet()
      return busy()
    }
    override fun collectContext(input: TerminalCommandInput): TerminalPromptContext {
      assertBackgroundAccess()
      contexts.incrementAndGet()
      return TerminalPromptContext(input.description, "bash", "/tmp", "test", emptyList())
    }
    override fun sendText(text: String) {
      check(ApplicationManager.getApplication().isDispatchThread)
      sent += text
      onSend()
    }
    override fun forwardTabs(input: TerminalCommandInput, count: Int): Boolean {
      check(ApplicationManager.getApplication().isDispatchThread)
      if (!focused || revision != input.revision) return false
      sendText("\t".repeat(count))
      return true
    }
  }

  private class FixedBackend(hold: Boolean = false) : CompletionBackend {
    override val provider = ProviderKind.CODEX
    val calls = AtomicInteger()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>().also { if (!hold) it.complete(Unit) }
    override suspend fun complete(prompt: CompletionPrompt, settings: AutocompleteSettings.SettingsState): BackendResult {
      assertBackgroundAccess()
      calls.incrementAndGet()
      entered.complete(Unit)
      release.await()
      return BackendResult.Success("pwd", settings.codexModel, "test")
    }
  }

  companion object {
    private fun assertBackgroundAccess() {
      val app = ApplicationManager.getApplication()
      check(!app.isDispatchThread) { "Process/filesystem probe ran on EDT" }
      check(!app.isReadAccessAllowed) { "Process/filesystem probe ran with read access" }
    }
  }
}
