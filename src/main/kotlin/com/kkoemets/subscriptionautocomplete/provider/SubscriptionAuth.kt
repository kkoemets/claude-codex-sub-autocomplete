package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object SubscriptionAuth {
  private data class CachedResult(val checkedAt: Long, val error: String?)

  private val cache = ConcurrentHashMap<String, CachedResult>()

  fun verifyClaude(
    executable: Path,
    workingDirectory: Path,
    forceRefresh: Boolean = false,
  ): String? = cached("claude:$executable", forceRefresh) {
    val result = ProcessRunner.run(
      command = listOf(executable.toString(), "auth", "status"),
      input = "",
      workingDirectory = workingDirectory,
      timeoutSeconds = STATUS_TIMEOUT_SECONDS,
      environmentTransform = BillingEnvironment::subscriptionOnlyClaude,
    )
    claudeAuthError(result)
  }

  internal fun claudeAuthError(result: ProcessResult): String? {
    if (result.timedOut) {
      return "Claude Code did not answer `claude auth status` within $STATUS_TIMEOUT_SECONDS seconds."
    }
    // A signed-out CLI exits non-zero but still prints its status; a CLI that could not start does not.
    val status = runCatching { JsonParser.parseString(result.stdout).asJsonObject }.getOrNull()
      ?: return if (result.exitCode != 0) {
        val detail = sequenceOf(result.stderr, result.stdout).flatMap { it.lineSequence() }
          .map(String::trim).lastOrNull(String::isNotEmpty)?.take(300)
        "Could not run `claude auth status` (exit code ${result.exitCode})" +
          (detail?.let { ": $it" } ?: ".") + " Check the Claude executable in the plugin settings."
      } else {
        "Could not verify Claude subscription authentication."
      }
    val loggedIn = status.get("loggedIn")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    if (!loggedIn || result.exitCode != 0) return "Claude Code is not signed in. Run `claude auth login`."
    val authMethod = status.get("authMethod")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
    val provider = status.get("apiProvider")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().lowercase()
    return if (authMethod == "claude.ai" && provider != "bedrock" && provider != "vertex") {
      null
    } else {
      "Claude Code must be signed in through a Claude subscription; API and cloud-provider credentials are disabled."
    }
  }

  fun verifyCodex(
    executable: Path,
    workingDirectory: Path,
    forceRefresh: Boolean = false,
  ): String? = cached("codex:$executable", forceRefresh) {
    val result = ProcessRunner.run(
      command = listOf(executable.toString(), "login", "status"),
      input = "",
      workingDirectory = workingDirectory,
      timeoutSeconds = STATUS_TIMEOUT_SECONDS,
      environmentTransform = BillingEnvironment::subscriptionOnlyCodex,
    )
    codexAuthError(result)
  }

  internal fun codexAuthError(result: ProcessResult): String? {
    val combined = sequenceOf(result.stdout, result.stderr)
      .flatMap { it.lineSequence() }
      .map(String::trim)
      .filter(String::isNotEmpty)
      .joinToString("\n")
      .lowercase()
    val base = "Codex must be signed in through ChatGPT. Run `codex login`; " +
      "API-key login is intentionally rejected."
    return when {
      result.timedOut -> "$base The `codex login status` check timed out."
      combined.contains("not logged in") || combined.contains("not authenticated") -> base
      result.exitCode == 0 && combined.contains("chatgpt") &&
        (combined.contains("logged in") || combined.contains("authenticated")) -> null
      combined.contains("api key") || combined.contains("api-key") ->
        "$base The CLI reports API-key authentication instead."
      result.exitCode != 0 -> "$base `codex login status` exited with code ${result.exitCode}."
      combined.isBlank() -> "$base `codex login status` returned no output."
      else -> "$base The CLI returned an unrecognized authentication status."
    }
  }

  private fun cached(key: String, forceRefresh: Boolean, probe: () -> String?): String? {
    val now = System.currentTimeMillis()
    cache[key]?.takeUnless { forceRefresh }?.let { cached ->
      val lifetime = if (cached.error == null) SUCCESS_CACHE_MILLIS else FAILURE_CACHE_MILLIS
      if (now - cached.checkedAt < lifetime) return cached.error
    }
    return probe().also { cache[key] = CachedResult(now, it) }
  }

  // A cold start of a Node-based CLI on Windows can take several seconds while it is being scanned.
  private const val STATUS_TIMEOUT_SECONDS = 10
  private const val SUCCESS_CACHE_MILLIS = 60_000L
  private const val FAILURE_CACHE_MILLIS = 5_000L
}
