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
      timeoutSeconds = 5,
      environmentTransform = BillingEnvironment::subscriptionOnlyClaude,
    )
    if (result.exitCode != 0 || result.timedOut) return@cached "Claude Code is not signed in. Run `claude auth login`."
    val status = runCatching { JsonParser.parseString(result.stdout).asJsonObject }.getOrNull()
      ?: return@cached "Could not verify Claude subscription authentication."
    val loggedIn = status.get("loggedIn")?.asBoolean == true
    val authMethod = status.get("authMethod")?.asString.orEmpty().lowercase()
    val provider = status.get("apiProvider")?.asString.orEmpty().lowercase()
    if (loggedIn && authMethod == "claude.ai" && provider != "bedrock" && provider != "vertex") {
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
      timeoutSeconds = 5,
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

  private const val SUCCESS_CACHE_MILLIS = 60_000L
  private const val FAILURE_CACHE_MILLIS = 5_000L
}
