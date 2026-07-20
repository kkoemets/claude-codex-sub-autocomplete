package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionOutputEnvelope
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicLong

class ClaudeBackend : CompletionBackend {
  override val provider: ProviderKind = ProviderKind.CLAUDE
  private val organizationDisabledUntil = AtomicLong()

  override suspend fun complete(
    prompt: CompletionPrompt,
    settings: AutocompleteSettings.SettingsState,
  ): BackendResult = try {
    runInterruptible(Dispatchers.IO) {
      if (System.nanoTime() < organizationDisabledUntil.get()) {
        return@runInterruptible BackendResult.Failure(ORGANIZATION_DISABLED_MESSAGE)
      }
      val executable = ExecutableResolver.resolve("claude", settings.claudeExecutable)
        ?: return@runInterruptible BackendResult.Failure("Claude Code executable was not found.")
      TemporaryWorkspace.use { workspace ->
        SubscriptionAuth.verifyClaude(executable, workspace)?.let {
          return@use BackendResult.Failure(it)
        }
        val model = settings.claudeModel.trim().ifEmpty { ProviderPolicy.DEFAULT_CLAUDE_MODEL }
        val settingsJson = JsonObject().apply {
          addProperty("model", model)
          add("availableModels", JsonArray().apply { add(model) })
          addProperty("enforceAvailableModels", true)
          add("fallbackModel", JsonArray())
        }.toString()
        val maxCharacters = CompletionOutputEnvelope.maxCharacters(settings.maxOutputTokens)
        val streamLimiter = ClaudeStreamLimiter(maxCharacters)
        val providerStartedAt = System.nanoTime()
        val result = ProcessRunner.runStreamingLines(
          command = listOf(
            executable.toString(),
            "-p",
            "--output-format",
            "stream-json",
            "--verbose",
            "--include-partial-messages",
            "--model",
            model,
            "--effort",
            "low",
            "--no-session-persistence",
            "--safe-mode",
            "--disable-slash-commands",
            "--tools",
            "",
            "--disallowedTools",
            "*",
            "--strict-mcp-config",
            "--permission-mode",
            "dontAsk",
            "--settings",
            settingsJson,
            "--system-prompt",
            prompt.systemPrompt + "\nReturn no more than ${settings.maxOutputTokens} approximate tokens.",
          ),
          input = prompt.userPrompt,
          workingDirectory = workspace,
          timeoutSeconds = settings.timeoutSeconds,
          environmentTransform = BillingEnvironment::subscriptionOnlyClaude,
          stopAfterLine = streamLimiter::observe,
        )
        val parsed = when {
          result.timedOut -> BackendResult.Failure("Claude completion timed out.")
          streamLimiter.exceededLimit() -> BackendResult.Failure(
            CompletionOutputEnvelope.failureMessage(
              "Claude",
              streamLimiter.observedCharacters(),
              maxCharacters,
            ),
          )
          result.stdout.isNotBlank() -> {
            val response = parseOutput(result.stdout, model, maxCharacters)
            if (
              result.exitCode != 0 &&
              response is BackendResult.Failure &&
              response.message == UNREADABLE_RESPONSE
            ) {
              BackendResult.Failure(cleanError(result.stderr, result.stdout))
            } else {
              response
            }
          }
          result.exitCode != 0 -> BackendResult.Failure(cleanError(result.stderr, result.stdout))
          else -> BackendResult.Failure(UNREADABLE_RESPONSE)
        }
        val timed = if (parsed is BackendResult.Success) {
          parsed.copy(
            timing = ProviderTiming(
              firstTokenMillis = streamLimiter.firstTokenMillis(providerStartedAt),
              totalMillis = (System.nanoTime() - providerStartedAt) / 1_000_000,
            ),
          )
        } else {
          parsed
        }
        if (timed is BackendResult.Failure && timed.message == ORGANIZATION_DISABLED_MESSAGE) {
          organizationDisabledUntil.set(System.nanoTime() + ORGANIZATION_DISABLED_COOLDOWN_NANOS)
        }
        timed
      }
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    BackendResult.Failure("Claude completion failed: ${error.message ?: error.javaClass.simpleName}")
  }

  internal fun parseResult(stdout: String, model: String): BackendResult {
    val response = runCatching { JsonParser.parseString(stdout).asJsonObject }.getOrNull()
      ?: return BackendResult.Failure(UNREADABLE_RESPONSE)
    if (response.get("is_error")?.asBoolean == true) {
      val message = response.get("result")?.asString ?: "Claude completion failed."
      return BackendResult.Failure(cleanSubscriptionError(message))
    }
    val text = response.get("result")?.asString.orEmpty()
    return if (text.isBlank()) BackendResult.Failure("Claude returned no completion.")
    else BackendResult.Success(text, model, "isolated JSON process")
  }

  internal fun parseOutput(
    stdout: String,
    model: String,
    maxCharacters: Int = Int.MAX_VALUE,
  ): BackendResult {
    val messages = stdout.lineSequence().mapNotNull { line ->
      runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
    }.toList()
    val result = messages.lastOrNull { it.get("type")?.asString == "result" }
    if (result != null) {
      if (result.get("is_error")?.asBoolean == true || result.get("subtype")?.asString?.startsWith("error") == true) {
        return BackendResult.Failure(cleanSubscriptionError(result.get("result")?.asString ?: "Claude completion failed."))
      }
      val text = result.get("result")?.asString.orEmpty()
      return when {
        text.isBlank() -> BackendResult.Failure("Claude returned no completion.")
        text.length > maxCharacters.coerceAtLeast(1) -> BackendResult.Failure(
          CompletionOutputEnvelope.failureMessage("Claude", text.length, maxCharacters.coerceAtLeast(1)),
        )
        else -> BackendResult.Success(text, model, "isolated stream-json")
      }
    }
    val partial = messages.asSequence()
      .filter { it.get("type")?.asString == "stream_event" }
      .mapNotNull { it.getAsJsonObject("event")?.getAsJsonObject("delta") }
      .filter { it.get("type")?.asString == "text_delta" }
      .mapNotNull { it.get("text")?.asString }
      .joinToString("")
    return when {
      partial.isBlank() -> BackendResult.Failure(UNREADABLE_RESPONSE)
      partial.length > maxCharacters.coerceAtLeast(1) -> BackendResult.Failure(
        CompletionOutputEnvelope.failureMessage("Claude", partial.length, maxCharacters.coerceAtLeast(1)),
      )
      else -> BackendResult.Success(partial, model, "isolated stream-json partial fallback")
    }
  }

  private fun cleanError(stderr: String, stdout: String): String =
    (stderr.ifBlank { stdout }).lineSequence().lastOrNull { it.isNotBlank() }?.take(500)
      ?: "Claude completion failed."

  private fun cleanSubscriptionError(message: String): String =
    if (message.contains("disabled Claude subscription access", ignoreCase = true)) {
      ORGANIZATION_DISABLED_MESSAGE
    } else {
      message
    }

  private companion object {
    const val UNREADABLE_RESPONSE = "Claude returned an unreadable response."
    const val ORGANIZATION_DISABLED_MESSAGE =
      "Claude subscription access is disabled for this organization. Ask its administrator to enable Claude Code subscription access."
    const val ORGANIZATION_DISABLED_COOLDOWN_NANOS = 60_000_000_000L
  }
}

internal class ClaudeStreamLimiter(private val maxCharacters: Int) {
  private var receivedCharacters = 0
  private var exceeded = false
  private var firstDeltaAtNanos: Long? = null

  fun observe(line: String): Boolean {
    val message = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return false
    if (message.get("type")?.asString == "result") return false
    if (message.get("type")?.asString != "stream_event") return false
    val delta = message.getAsJsonObject("event")?.getAsJsonObject("delta") ?: return false
    if (delta.get("type")?.asString != "text_delta") return false
    if (firstDeltaAtNanos == null) firstDeltaAtNanos = System.nanoTime()
    receivedCharacters += delta.get("text")?.asString.orEmpty().length
    exceeded = receivedCharacters > maxCharacters.coerceAtLeast(1)
    return exceeded
  }

  fun exceededLimit(): Boolean = exceeded

  fun observedCharacters(): Int = receivedCharacters

  fun firstTokenMillis(startedAtNanos: Long): Long? =
    firstDeltaAtNanos?.let { (it - startedAtNanos).coerceAtLeast(0) / 1_000_000 }
}
