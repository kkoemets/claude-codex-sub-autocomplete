package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.CodexBackend
import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

object CodexLifecycleSmoke {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val backend = CodexBackend()
    val settings = AutocompleteSettings.SettingsState(
      codexModel = System.getProperty("eval.codexModel", ProviderPolicy.DEFAULT_CODEX_MODEL),
      codexReasoningEffort = System.getProperty(
        "eval.codexReasoningEffort",
        ProviderPolicy.DEFAULT_CODEX_EFFORT,
      ),
      timeoutSeconds = 20,
      maxOutputTokens = 32,
    )
    val prompt = CompletionPrompt(
      systemPrompt = "Return only a short exact code-completion suffix. Do not call tools.",
      userPrompt = "<code_before_cursor># Install dependencies before</code_before_cursor>" +
        "<CURSOR><code_after_cursor>\nRUN apk update</code_after_cursor>",
      mode = CompletionMode.AUTOMATIC,
    )
    try {
      repeat(3) { index ->
        var response: BackendResult? = null
        val elapsed = measureTimeMillis { response = backend.complete(prompt, settings) }
        check(response is BackendResult.Success) { "Codex lifecycle request ${index + 1} failed: $response" }
        delay(250)
        check(nodeReplDescendants().isEmpty()) {
          "Codex request ${index + 1} leaked node_repl descendants: ${nodeReplDescendants()}"
        }
        println("request ${index + 1}: ${elapsed} ms; no node_repl descendants")
      }

      val concurrent = listOf(
        async(Dispatchers.IO) { backend.complete(prompt, settings) },
        async(Dispatchers.IO) { backend.complete(prompt, settings) },
      ).awaitAll()
      check(concurrent.all { it is BackendResult.Success }) {
        "Concurrent Codex lifecycle requests failed: $concurrent"
      }
      delay(250)
      check(nodeReplDescendants().isEmpty()) {
        "Concurrent Codex requests leaked node_repl descendants: ${nodeReplDescendants()}"
      }
      println("concurrency: two isolated app-server sessions completed without node_repl descendants")

      val cancelled = async(Dispatchers.IO) { backend.complete(prompt, settings.copy(maxOutputTokens = 128)) }
      delay(150)
      cancelled.cancelAndJoin()
      delay(500)
      check(nodeReplDescendants().isEmpty()) {
        "Canceled Codex request leaked node_repl descendants: ${nodeReplDescendants()}"
      }
      println("cancellation: no node_repl descendants")
    } finally {
      backend.close()
    }
    delay(250)
    check(codexAppServerDescendants().isEmpty()) {
      "Codex app-server remained after backend close: ${codexAppServerDescendants()}"
    }
    check(nodeReplDescendants().isEmpty()) {
      "node_repl remained after backend close: ${nodeReplDescendants()}"
    }
    println("backend close: no Codex app-server or node_repl descendants")
  }

  private fun nodeReplDescendants(): List<String> = descendantsContaining("node_repl")

  private fun codexAppServerDescendants(): List<String> = descendantsContaining("app-server")

  private fun descendantsContaining(fragment: String): List<String> = ProcessHandle.current()
    .descendants()
    .map { handle -> handle.info().commandLine().orElse("") }
    .filter { command -> command.contains(fragment, ignoreCase = true) }
    .toList()
}
