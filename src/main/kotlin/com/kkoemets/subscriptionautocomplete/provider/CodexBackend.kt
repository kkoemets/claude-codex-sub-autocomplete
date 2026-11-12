package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionOutputEnvelope
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Path

class CodexBackend : CompletionBackend, AutoCloseable {
  override val provider: ProviderKind = ProviderKind.CODEX
  private val appServer = CodexAppServerClient()

  override suspend fun complete(
    prompt: CompletionPrompt,
    settings: AutocompleteSettings.SettingsState,
  ): BackendResult = try {
    runInterruptible(Dispatchers.IO) {
      val executable = ExecutableResolver.resolve("codex", settings.codexExecutable)
        ?: return@runInterruptible BackendResult.Failure("Codex executable was not found.")
      val authError = TemporaryWorkspace.use { workspace ->
        SubscriptionAuth.verifyCodex(executable, workspace)
      }
      if (authError != null) {
        return@runInterruptible BackendResult.Failure(authError)
      }
      val model = settings.codexModel.trim().ifEmpty { ProviderPolicy.DEFAULT_CODEX_MODEL }
      if (!ProviderPolicy.isValidModelName(model)) {
        return@runInterruptible BackendResult.Failure(ProviderPolicy.invalidModelMessage("Codex", model))
      }
      val effort = settings.codexReasoningEffort.trim().ifEmpty { ProviderPolicy.DEFAULT_CODEX_EFFORT }
      if (!ProviderPolicy.isValidReasoningEffort(effort)) {
        return@runInterruptible BackendResult.Failure(
          "Codex reasoning effort \"${effort.take(40)}\" is not valid. " +
            "Choose one of ${ProviderPolicy.codexReasoningEfforts.joinToString()} in the plugin settings.",
        )
      }
      try {
        appServer.complete(
          executable,
          prompt,
          model,
          effort,
          settings.timeoutSeconds,
          settings.maxOutputTokens,
        )
      } catch (_: CodexAppServerUnavailableException) {
        completeOneShot(
          executable,
          prompt,
          model,
          effort,
          settings.timeoutSeconds,
          settings.maxOutputTokens,
        )
      }
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    BackendResult.Failure("Codex completion failed: ${error.message ?: error.javaClass.simpleName}")
  }

  override fun close() {
    appServer.close()
  }

  private fun completeOneShot(
    executable: Path,
    prompt: CompletionPrompt,
    model: String,
    effort: String,
    timeoutSeconds: Int,
    maxOutputTokens: Int,
  ): BackendResult = TemporaryWorkspace.use { workspace ->
    val result = ProcessRunner.run(
      command = oneShotCommand(executable, model, effort),
      input = prompt.combined() + "\n\nReturn no more than $maxOutputTokens approximate tokens.",
      workingDirectory = workspace,
      timeoutSeconds = timeoutSeconds,
      environmentTransform = BillingEnvironment::subscriptionOnlyCodex,
    )
    when {
      result.timedOut -> BackendResult.Failure("Codex completion timed out.")
      result.exitCode != 0 -> BackendResult.Failure(cleanError(result.stderr, result.stdout))
      else -> parseJsonLines(
        result.stdout,
        model,
        CompletionOutputEnvelope.maxCharacters(maxOutputTokens),
      )
    }.let { parsed ->
      if (parsed is BackendResult.Success) parsed.copy(transport = "one-shot exec fallback") else parsed
    }
  }

  internal fun oneShotCommand(executable: Path, model: String, effort: String): List<String> = listOf(
    executable.toString(),
    "exec",
    "--ephemeral",
    "--ignore-user-config",
    "--ignore-rules",
    "--sandbox",
    "read-only",
    "--skip-git-repo-check",
    "--model",
    model,
    "--config",
    // Unquoted on purpose: Codex reads a value that is not valid TOML as a literal string, and a
    // quoted value loses its quotes on Windows. The effort is validated as a plain lowercase word.
    "model_reasoning_effort=$effort",
    "--json",
    "-",
  )

  internal fun parseJsonLines(
    stdout: String,
    model: String,
    maxCharacters: Int = Int.MAX_VALUE,
  ): BackendResult {
    val messages = stdout.lineSequence().mapNotNull { line ->
      val event = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
        ?: return@mapNotNull null
      if (event.get("type")?.asString != "item.completed") return@mapNotNull null
      val item = event.getAsJsonObject("item") ?: return@mapNotNull null
      if (item.get("type")?.asString != "agent_message") return@mapNotNull null
      item.get("text")?.asString
    }.toList()
    val text = messages.lastOrNull().orEmpty()
    return when {
      text.isBlank() -> BackendResult.Failure("Codex returned no completion.")
      text.length > maxCharacters.coerceAtLeast(1) -> BackendResult.Failure(
        CompletionOutputEnvelope.failureMessage("Codex", text.length, maxCharacters.coerceAtLeast(1)),
      )
      else -> BackendResult.Success(text, model)
    }
  }

  private fun cleanError(stderr: String, stdout: String): String =
    (stderr.ifBlank { stdout }).lineSequence().lastOrNull { it.isNotBlank() }?.take(500)
      ?: "Codex completion failed."
}
