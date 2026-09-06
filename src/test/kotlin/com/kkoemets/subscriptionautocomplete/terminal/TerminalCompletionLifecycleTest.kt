package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.CompletionBackend
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** Plugin unload can end the service lifetime while the project and terminal remain open. */
class TerminalCompletionLifecycleTest : BasePlatformTestCase() {
  private lateinit var originalSettings: AutocompleteSettings.SettingsState
  private lateinit var scope: CoroutineScope
  private lateinit var service: TerminalCompletionService
  private val backendRelease = CompletableDeferred<Unit>()

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
    // Restore the light project's original service at teardown rather than caching a disposed one.
    project.replaceService(TerminalCompletionService::class.java, service, testRootDisposable)
  }

  override fun tearDown() {
    try {
      scope.cancel()
      backendRelease.complete(Unit)
      runBlocking {
        awaitFinished()
        withContext(Dispatchers.EDT) {
          if (!Disposer.isDisposed(service)) Disposer.dispose(service)
        }
      }
      AutocompleteSettings.getInstance().loadState(originalSettings)
    } finally {
      super.tearDown()
    }
  }

  fun testServiceDisposalRemovesBothInstalledCallbacksWithoutClosingProject() = runBlocking {
    withContext(Dispatchers.EDT) {
      val queue = IdeEventQueue.getInstance()
      val manager = TerminalToolWindowManager.getInstance(project)
      val dispatchersBefore = callbacks(queue, "nonLockingDispatchers").toList()
      val handlersBefore = callbacks(manager, "myTerminalSetupHandlers").toList()

      TerminalWidgetTabInstaller(project).install()
      val installedDispatchers = callbacks(queue, "nonLockingDispatchers")
        .filterNot { added -> dispatchersBefore.any { it === added } }
      val installedHandlers = callbacks(manager, "myTerminalSetupHandlers")
        .filterNot { added -> handlersBefore.any { it === added } }
      try {
        assertEquals("Installer must register its actual event dispatcher", 1, installedDispatchers.size)
        assertEquals("Installer must register its actual terminal setup handler", 1, installedHandlers.size)
        assertFalse(project.isDisposed)

        Disposer.dispose(service)

        assertFalse("Unloading the plugin must not require closing the project", project.isDisposed)
        assertTrue(Disposer.isDisposed(service))
        assertTrue(
          "A disposed service must not leave its terminal dispatcher handling application events",
          callbacks(queue, "nonLockingDispatchers").none { remaining ->
            installedDispatchers.any { it === remaining }
          },
        )
        assertTrue(
          "A disposed service must not leave its handler attached to future terminal sessions",
          callbacks(manager, "myTerminalSetupHandlers").none { remaining ->
            installedHandlers.any { it === remaining }
          },
        )
      } finally {
        // Also clean up if the old project-owned registration makes either assertion fail.
        installedDispatchers.forEach { queue.removeDispatcher(it as IdeEventQueue.NonLockedEventDispatcher) }
        callbacks(manager, "myTerminalSetupHandlers").removeAll { remaining ->
          installedHandlers.any { it === remaining }
        }
      }
    }
  }

  fun testScopeCancellationClosesPendingListenerWithoutTerminalInsertion() = runBlocking {
    val terminal = WatchedTerminal()
    val backend = HeldBackend(ignoreCancellation = false)
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    withTimeout(5_000) { backend.entered.await() }
    assertEquals(1, terminal.listeners.get())

    // The platform cancels the injected service scope during dynamic plugin unload.
    scope.cancel()
    awaitFinished()

    assertFalse(project.isDisposed)
    assertEquals(0, terminal.listeners.get())
    assertEquals(1, terminal.closedListeners.get())
    assertEquals(0, backend.responses.get())
    assertEmpty(terminal.sent)
  }

  fun testLateBackendResponseAfterCancellationCannotInsertIntoLiveTerminal() = runBlocking {
    val terminal = WatchedTerminal()
    val backend = HeldBackend(ignoreCancellation = true)
    withContext(Dispatchers.EDT) { assertTrue(service.request(terminal, backend = { backend })) }
    withTimeout(5_000) { backend.entered.await() }
    assertEquals(1, terminal.listeners.get())

    scope.cancel()
    backendRelease.complete(Unit)
    awaitFinished()

    assertFalse(project.isDisposed)
    assertEquals("Backend really returned a response after cancellation", 1, backend.responses.get())
    assertEquals(0, terminal.listeners.get())
    assertEquals(1, terminal.closedListeners.get())
    assertEmpty(terminal.sent)
  }

  private suspend fun awaitFinished() = withTimeout(5_000) {
    while (service.isRunning()) delay(5)
  }

  // These SDK collections have no public snapshot accessor. Reflection stays in this regression.
  @Suppress("UNCHECKED_CAST")
  private fun callbacks(owner: Any, fieldName: String): MutableList<Any> =
    owner.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(owner) as MutableList<Any>

  private class WatchedTerminal : TerminalCompletionTarget {
    val listeners = AtomicInteger()
    val closedListeners = AtomicInteger()
    val sent = mutableListOf<String>()
    override fun captureInput(): TerminalCommandInput {
      check(ApplicationManager.getApplication().isDispatchThread)
      return TerminalCommandInput("# print working directory", "print working directory", 0, 0)
    }
    override fun watchChanges(): AutoCloseable {
      check(ApplicationManager.getApplication().isDispatchThread)
      listeners.incrementAndGet()
      return AutoCloseable {
        closedListeners.incrementAndGet()
        listeners.decrementAndGet()
      }
    }
    override fun isCommandRunning(): Boolean = false
    override fun collectContext(input: TerminalCommandInput) =
      TerminalPromptContext(input.description, "bash", "/tmp", "test", emptyList())
    override fun sendText(text: String) {
      check(ApplicationManager.getApplication().isDispatchThread)
      sent += text
    }
  }

  private inner class HeldBackend(private val ignoreCancellation: Boolean) : CompletionBackend {
    override val provider = ProviderKind.CODEX
    val entered = CompletableDeferred<Unit>()
    val responses = AtomicInteger()
    override suspend fun complete(prompt: CompletionPrompt, settings: AutocompleteSettings.SettingsState): BackendResult {
      entered.complete(Unit)
      if (ignoreCancellation) withContext(NonCancellable) { backendRelease.await() }
      else backendRelease.await()
      responses.incrementAndGet()
      return BackendResult.Success("pwd", settings.codexModel, "test")
    }
  }
}
