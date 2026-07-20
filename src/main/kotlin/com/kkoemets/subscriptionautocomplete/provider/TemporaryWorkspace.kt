package com.kkoemets.subscriptionautocomplete.provider

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

object TemporaryWorkspace {
  fun <T> use(block: (Path) -> T): T {
    val directory = Files.createTempDirectory("subscription-autocomplete-")
    return try {
      block(directory)
    } finally {
      runCatching {
        Files.walk(directory).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }
}
