package com.kkoemets.subscriptionautocomplete.context

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Returns null when [block] fails, but never hides cancellation.
 *
 * `runCatching` also catches [ProcessCanceledException]. Inside a read action that turns "a write
 * action is waiting, abort and retry" into an ordinary null, so the action carries on with partial
 * results that then get cached or treated as a successful check.
 */
internal inline fun <T> nullOnFailure(block: () -> T): T? = try {
  block()
} catch (cancelled: ProcessCanceledException) {
  throw cancelled
} catch (cancelled: CancellationException) {
  throw cancelled
} catch (_: Exception) {
  null
}
