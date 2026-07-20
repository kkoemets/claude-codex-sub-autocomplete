package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.completion.CompletionMode
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.ClaudeBackend
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

object ClaudeLifecycleSmoke {
  @JvmStatic
  fun main(args: Array<String>) = runBlocking {
    val backend = ClaudeBackend()
    val settings = AutocompleteSettings.SettingsState(
      claudeModel = "haiku",
      timeoutSeconds = 20,
      maxOutputTokens = 32,
    )
    val prompt = CompletionPrompt(
      systemPrompt = "Return only a short exact code-completion suffix. Do not call tools.",
      userPrompt = "<code_before_cursor>const doubled = values.</code_before_cursor>" +
        "<CURSOR><code_after_cursor></code_after_cursor>",
      mode = CompletionMode.AUTOMATIC,
    )

    var response: BackendResult? = null
    val elapsed = measureTimeMillis { response = backend.complete(prompt, settings) }
    check(response is BackendResult.Success) { "Claude lifecycle request failed: $response" }
    delay(250)
    check(claudeDescendants().isEmpty()) {
      "Claude request left subprocesses alive: ${claudeDescendants()}"
    }
    println("completion: $elapsed ms; no Claude subprocess descendants")

    val cancelled = async(Dispatchers.IO) {
      backend.complete(
        prompt.copy(userPrompt = prompt.userPrompt + " Return a long list of alternatives."),
        settings.copy(maxOutputTokens = 128),
      )
    }
    delay(100)
    cancelled.cancelAndJoin()
    delay(500)
    check(claudeDescendants().isEmpty()) {
      "Canceled Claude request left subprocesses alive: ${claudeDescendants()}"
    }
    println("cancellation: no Claude subprocess descendants")
  }

  private fun claudeDescendants(): List<String> = ProcessHandle.current()
    .descendants()
    .map { handle -> handle.info().commandLine().orElse("") }
    .filter { command -> command.contains("/claude", ignoreCase = true) }
    .toList()
}
