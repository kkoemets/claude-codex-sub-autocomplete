package com.kkoemets.subscriptionautocomplete.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DiagnosticsDialogLifecycleTest {
  @Test
  fun `closing one dialog cancels its suspended request while another stays usable`() = withService { service, dispatcher, scope ->
    val first = DialogDisposable()
    val second = DialogDisposable()
    val firstScope = service.createScope(first)
    val secondScope = service.createScope(second)
    val response = CompletableDeferred<Unit>()
    var started = false
    var updated = false
    var cancelled = false
    val request = firstScope.launch {
      started = true
      try {
        response.await()
        updated = true
      } finally {
        cancelled = !isActive
      }
    }
    dispatcher.runQueued()
    assertTrue(started)

    Disposer.dispose(first)
    dispatcher.runQueued()
    response.complete(Unit)
    var otherUpdated = false
    secondScope.launch { otherUpdated = true }
    dispatcher.runQueued()

    assertTrue(request.isCancelled)
    assertTrue(cancelled)
    assertFalse(updated)
    assertTrue(otherUpdated)
    assertTrue(scope.isActive)
    assertEquals(1, first.disposals)
    assertEquals(0, second.disposals)
  }

  @Test
  fun `unload closes reparented dialogs and prevents queued callbacks from starting`() = withService { service, dispatcher, scope ->
    val client = Disposer.newDisposable()
    try {
      val dialog = DialogDisposable()
      val dialogScope = service.createScope(dialog)
      // DialogWrapper.show can attach its disposable to the IDE client after plugin registration.
      Disposer.register(client, dialog)
      var updated = false
      val queuedUpdate = dialogScope.launch { updated = true }

      Disposer.dispose(service)
      dispatcher.runQueued()

      assertEquals(1, dialog.disposals)
      assertFalse(Disposer.isDisposed(client))
      assertTrue(queuedUpdate.isCancelled)
      assertFalse(updated)
      assertTrue(scope.isActive)
    } finally {
      Disposer.dispose(client)
    }
  }

  @Test
  fun `injected service scope cancellation reaches dialog work before disposal`() = withService { service, dispatcher, scope ->
    val dialog = DialogDisposable()
    val dialogScope = service.createScope(dialog)
    var updated = false
    val queuedUpdate = dialogScope.launch { updated = true }

    scope.cancel()
    dispatcher.runQueued()

    assertTrue(queuedUpdate.isCancelled)
    assertFalse(updated)
    assertEquals(0, dialog.disposals)
    Disposer.dispose(service)
    assertEquals(1, dialog.disposals)
  }

  private fun withService(block: (DiagnosticsDialogService, QueuedDispatcher, CoroutineScope) -> Unit) {
    val dispatcher = QueuedDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val service = DiagnosticsDialogService(scope)
    try {
      block(service, dispatcher, scope)
    } finally {
      Disposer.dispose(service)
      scope.cancel()
      dispatcher.runQueued()
    }
  }

  private class DialogDisposable : Disposable {
    var disposals = 0
    override fun dispose() {
      disposals++
    }
  }

  private class QueuedDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
      queued.addLast(block)
    }

    fun runQueued() {
      while (queued.isNotEmpty()) queued.removeFirst().run()
    }
  }
}
