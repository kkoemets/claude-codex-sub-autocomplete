package com.kkoemets.subscriptionautocomplete.provider

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

object TemporaryWorkspace {
  fun <T> use(block: (Path) -> T): T {
    val directory = Files.createTempDirectory("subscription-autocomplete-")
    return try {
      block(directory)
    } finally {
      delete(directory)
    }
  }

  /**
   * Removes a provider workspace, which can hold the request's prompt files.
   *
   * Windows refuses to delete a directory while a just-terminated process still holds it as its
   * working directory, so a failed attempt is retried briefly instead of leaving the files behind.
   */
  internal fun delete(directory: Path) {
    repeat(DELETE_ATTEMPTS) { attempt ->
      if (deleteTree(directory)) return
      if (attempt < DELETE_ATTEMPTS - 1) {
        try {
          Thread.sleep(DELETE_RETRY_MILLIS)
        } catch (_: InterruptedException) {
          // Cancellation must not skip cleanup: finish the remaining attempts without waiting.
          Thread.currentThread().interrupt()
        }
      }
    }
  }

  private fun deleteTree(directory: Path): Boolean = try {
    if (Files.exists(directory)) {
      Files.walk(directory).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
    true
  } catch (_: IOException) {
    false
  } catch (_: java.io.UncheckedIOException) {
    false
  }

  private const val DELETE_ATTEMPTS = 5
  private const val DELETE_RETRY_MILLIS = 100L
}
