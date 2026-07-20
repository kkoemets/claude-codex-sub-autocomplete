package com.kkoemets.subscriptionautocomplete.provider

import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsLog
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlin.time.measureTimedValue

data class ConnectivityResult(
  val successful: Boolean,
  val summary: String,
  val details: String,
)

object ProviderConnectivityTester {
  suspend fun testLogin(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): ConnectivityResult = try {
    runInterruptible(Dispatchers.IO) {
      val command = if (provider == ProviderKind.CLAUDE) "claude" else "codex"
      val configuredPath = if (provider == ProviderKind.CLAUDE) {
        settings.claudeExecutable
      } else {
        settings.codexExecutable
      }
      val executable = ExecutableResolver.resolve(command, configuredPath)
        ?: return@runInterruptible failure(
          "$command executable was not found",
          "Configure its absolute path or add it to PATH.",
        )
      TemporaryWorkspace.use { workspace ->
        val environmentTransform = if (provider == ProviderKind.CLAUDE) {
          BillingEnvironment::subscriptionOnlyClaude
        } else {
          BillingEnvironment::subscriptionOnlyCodex
        }
        val version = ProcessRunner.run(
          command = listOf(executable.toString(), "--version"),
          input = "",
          workingDirectory = workspace,
          timeoutSeconds = 5,
          environmentTransform = environmentTransform,
        )
        if (version.timedOut || version.exitCode != 0) {
          return@use failure(
            "$command executable could not be started",
            version.stderr.ifBlank { version.stdout }.lineSequence().firstOrNull().orEmpty(),
          )
        }
        val authError = when (provider) {
          ProviderKind.CLAUDE -> SubscriptionAuth.verifyClaude(executable, workspace, forceRefresh = true)
          ProviderKind.CODEX -> SubscriptionAuth.verifyCodex(executable, workspace, forceRefresh = true)
        }
        val versionText = version.stdout.ifBlank { version.stderr }.lineSequence().firstOrNull().orEmpty()
        if (authError != null) {
          return@use failure(
            "Subscription login check failed",
            "Executable: $executable\nVersion: $versionText\n$authError",
          )
        }
        success(
          "${provider.displayName} login is ready",
          "Executable: $executable\nVersion: $versionText\nAuthentication: subscription",
        )
      }
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    failure("Connectivity check failed", error.message ?: error.javaClass.simpleName)
  }

  suspend fun testCompletion(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): ConnectivityResult {
    val login = testLogin(provider, settings)
    if (!login.successful) return login
    val prompt = CompletionPrompt(
      systemPrompt = "Return only the word OK. Do not call tools.",
      userPrompt = "Connectivity test. Return only OK.",
    )
    val timed = measureTimedValue {
      BackendRegistry.forProvider(provider).complete(prompt, settings.copy(timeoutSeconds = settings.timeoutSeconds.coerceAtLeast(20)))
    }
    return when (val result = timed.value) {
      is BackendResult.Success -> success(
        "${provider.displayName} model connection succeeded",
        "Model: ${result.model}\nTransport: ${result.transport.ifBlank { "provider default" }}\n" +
          "Duration: ${timed.duration.inWholeMilliseconds} ms\nResponse: ${result.text.take(80)}",
      )
      is BackendResult.Failure -> failure(
        "${provider.displayName} model connection failed",
        "Duration: ${timed.duration.inWholeMilliseconds} ms\n${result.message}",
      )
    }
  }

  private fun success(summary: String, details: String): ConnectivityResult =
    ConnectivityResult(true, summary, details).also {
      DiagnosticsLog.getInstance().info(summary, details)
    }

  private fun failure(summary: String, details: String): ConnectivityResult =
    ConnectivityResult(false, summary, details).also {
      DiagnosticsLog.getInstance().warning(summary, details)
    }
}
