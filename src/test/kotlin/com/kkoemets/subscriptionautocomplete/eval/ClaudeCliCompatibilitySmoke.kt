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
import java.nio.file.Files

/**
 * Checks the installed Claude CLI against the production invocation on the current operating system.
 *
 * The default run makes no model request. `claude.liveRequest=true` adds exactly one real
 * completion through the production backend, which is the end-to-end proof on a given machine
 * (run it on Windows with each installed launcher).
 */
object ClaudeCliCompatibilitySmoke {
  @JvmStatic
  fun main(args: Array<String>) {
    val executable = requireNotNull(ExecutableResolver.resolve("claude", System.getProperty("claude.executable", ""))) {
      "Claude Code executable was not found"
    }
    val backend = ClaudeBackend()
    // Quotes, a line break and cmd.exe metacharacters: everything a launcher could rewrite.
    val prompt = CompletionPrompt(
      "Reply with the single word \"OK\" and nothing else.\nNo punctuation & no 100% extras.",
      "Return only OK.",
    )
    println("Runtime: ${System.getProperty("os.name")}/${System.getProperty("os.arch")}; Java ${System.getProperty("java.version")}")
    println("Launcher: ${executable.fileName}")
    TemporaryWorkspace.use { workspace ->
      fun run(command: List<String>, input: String = "") = ProcessRunner.run(
        command, input, workspace, 30, BillingEnvironment::subscriptionOnlyClaude,
      )
      val version = run(listOf(executable.toString(), "--version"))
      check(version.exitCode == 0 && !version.timedOut) { "Claude version probe failed: ${version.stderr.take(300)}" }
      println("Installed CLI: ${version.stdout.trim()}")
      val help = run(listOf(executable.toString(), "--help"))
      check(help.exitCode == 0 && !help.timedOut) { "Claude help probe failed" }
      val flags = backend.prepareCommand(executable, workspace, prompt, ProviderPolicy.DEFAULT_CLAUDE_MODEL, 32)
        .filter { it.startsWith("--") }
      val undocumented = flags.filterNot { flag -> documentedForms(flag).any(help.stdout::contains) }
      check(undocumented.isEmpty()) { "Installed CLI does not document required flags: $undocumented" }
      println("Production CLI flags: ${flags.size}/${flags.size} supported")

      // --help still resolves --settings, but never requests a model completion, so this exercises
      // the real argument transport and working directory even when the user is logged in.
      val failures = mutableListOf<String>()
      for (model in ProviderPolicy.claudeModels) {
        val command = backend.prepareCommand(executable, workspace, prompt, model, 32)
        val response = runCatching { run(command + "--help") }
        val result = response.getOrNull()
        val accepted = result != null && result.exitCode == 0 && !result.timedOut && result.stdout.contains("--settings")
        println("$model production arguments: ${if (accepted) "PASS" else "FAIL"}")
        if (!accepted) {
          val detail = result?.let { (it.stderr + "\n" + it.stdout).trim().take(500) }
            ?: response.exceptionOrNull()?.message.orEmpty()
          failures += "$model: $detail"
        }
      }
      check(failures.isEmpty()) { failures.joinToString("\n") }

      val missingSettings = backend.prepareCommand(executable, workspace, prompt, ProviderPolicy.DEFAULT_CLAUDE_MODEL, 32)
      Files.delete(workspace.resolve(ClaudeBackend.SETTINGS_FILE))
      val rejected = run(missingSettings + "--help")
      check(!rejected.timedOut && (rejected.stderr + rejected.stdout).contains("Settings file not found")) {
        "Negative control failed: --help must resolve the settings file for this probe to be meaningful"
      }
      println("Missing settings file negative control: PASS")
      println("Settings argument transport passed without a model request.")

      if (System.getProperty("claude.liveRequest", "false").toBoolean()) {
        val settings = AutocompleteSettings.SettingsState(claudeExecutable = executable.toString(), timeoutSeconds = 60)
        when (val result = runBlocking { backend.complete(prompt, settings) }) {
          is BackendResult.Success -> {
            check(result.text.trim().trim('"', '.').equals("OK", ignoreCase = true)) {
              "The system prompt file was not honoured; the model answered: ${result.text.take(120)}"
            }
            println("Live request (${result.model}, ${result.transport}): PASS in ${result.timing.totalMillis} ms")
          }
          is BackendResult.Failure -> error("Live request failed: ${result.message}")
        }
      }
      if (System.getProperty("claude.argumentsOnly", "false").toBoolean()) return@use

      val auth = run(listOf(executable.toString(), "auth", "status"))
      check(!auth.timedOut) { "Claude authentication status timed out" }
      val status = JsonParser.parseString(auth.stdout).asJsonObject
      if (status.get("loggedIn")?.asBoolean != false) {
        println("Signed-out probes skipped: the CLI has saved authentication.")
        return@use
      }
      check(SubscriptionAuth.verifyClaude(executable, workspace, forceRefresh = true) != null)
      val signedOut = runBlocking {
        backend.complete(prompt, AutocompleteSettings.SettingsState(claudeExecutable = executable.toString()))
      }
      check(signedOut is BackendResult.Failure && signedOut.message.contains("claude auth login")) {
        "The backend did not report actionable signed-out guidance"
      }
      println("Real signed-out authentication: rejected with login guidance")
      for (model in ProviderPolicy.claudeModels) {
        // With loggedIn=false this exercises option/settings parsing and the CLI's authentication error.
        val response = run(backend.prepareCommand(executable, workspace, prompt, model, 32), prompt.userPrompt)
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
      println("CLI compatibility passed.")
    }
  }

  /** The CLI help lists `--system-prompt-file` only in its combined `--system-prompt[-file]` form. */
  private fun documentedForms(flag: String): List<String> =
    if (flag.endsWith("-file")) listOf(flag, flag.removeSuffix("-file") + "[-file]") else listOf(flag)
}
