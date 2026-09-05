package com.kkoemets.subscriptionautocomplete.eval

import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import com.kkoemets.subscriptionautocomplete.provider.BackendResult
import com.kkoemets.subscriptionautocomplete.provider.BillingEnvironment
import com.kkoemets.subscriptionautocomplete.provider.ClaudeBackend
import com.kkoemets.subscriptionautocomplete.provider.ExecutableResolver
import com.kkoemets.subscriptionautocomplete.provider.ProcessRunner
import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.provider.SubscriptionAuth
import com.kkoemets.subscriptionautocomplete.provider.TemporaryWorkspace
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import kotlinx.coroutines.runBlocking

/** Checks the installed CLI contract without requesting an authenticated model completion. */
object ClaudeCliCompatibilitySmoke {
  @JvmStatic
  fun main(args: Array<String>) {
    val executable = requireNotNull(ExecutableResolver.resolve("claude", System.getProperty("claude.executable", ""))) {
      "Claude Code executable was not found"
    }
    val backend = ClaudeBackend()
    val prompt = CompletionPrompt("Return only insertion text. Do not call tools.", "Return only OK.")
    TemporaryWorkspace.use { workspace ->
      fun run(command: List<String>, input: String = "") = ProcessRunner.run(
        command, input, workspace, 15, BillingEnvironment::subscriptionOnlyClaude,
      )
      val version = run(listOf(executable.toString(), "--version"))
      check(version.exitCode == 0 && !version.timedOut) { "Claude version probe failed" }
      println("Installed CLI: ${version.stdout.trim()}")
      val help = run(listOf(executable.toString(), "--help"))
      check(help.exitCode == 0 && !help.timedOut) { "Claude help probe failed" }
      val flags = backend.command(executable, prompt, ProviderPolicy.DEFAULT_CLAUDE_MODEL, 32)
        .filter { it.startsWith("--") }
      check(flags.all { help.stdout.contains(it) }) {
        "Installed CLI does not document required flags: ${flags.filterNot(help.stdout::contains)}"
      }
      println("Production CLI flags: ${flags.size}/${flags.size} supported")

      val auth = run(listOf(executable.toString(), "auth", "status"))
      check(!auth.timedOut) { "Claude authentication status timed out" }
      val status = JsonParser.parseString(auth.stdout).asJsonObject
      if (status.get("loggedIn")?.asBoolean != false) {
        println("Signed-out probes skipped: the CLI has saved authentication. No model request was made.")
        return@use
      }
      check(SubscriptionAuth.verifyClaude(executable, workspace, forceRefresh = true) != null)
      val rejected = runBlocking {
        backend.complete(prompt, AutocompleteSettings.SettingsState(claudeExecutable = executable.toString()))
      }
      check(rejected is BackendResult.Failure && rejected.message.contains("claude auth login")) {
        "The backend did not report actionable signed-out guidance"
      }
      println("Real signed-out authentication: rejected with login guidance")
      for (model in ProviderPolicy.claudeModels) {
        // With loggedIn=false this exercises option/settings parsing and the CLI's authentication error.
        val response = run(backend.command(executable, prompt, model, 32), prompt.userPrompt)
        check(response.exitCode != 0 && !response.timedOut) { "Expected a prompt authentication failure for $model" }
        val detail = response.stdout + "\n" + response.stderr
        check(!Regex("unknown option|unrecognized option|invalid option", RegexOption.IGNORE_CASE).containsMatchIn(detail)) {
          "Claude rejected the production arguments for $model"
        }
        check(Regex("not logged in|login|log in|sign in|authenticate", RegexOption.IGNORE_CASE).containsMatchIn(detail)) {
          "Claude failed before the expected authentication check for $model"
        }
        println("$model: production invocation accepted; signed-out error returned")
      }
      println("CLI compatibility passed. Authenticated model quality was not tested.")
    }
  }
}
