package com.kkoemets.subscriptionautocomplete.provider

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ProcessResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String,
  val timedOut: Boolean,
  val terminatedEarly: Boolean = false,
)

object ProcessRunner {
  fun run(
    command: List<String>,
    input: String,
    workingDirectory: Path,
    timeoutSeconds: Int,
    environmentTransform: (MutableMap<String, String>) -> Unit,
  ): ProcessResult {
    return runInternal(
      command,
      input,
      workingDirectory,
      timeoutSeconds,
      environmentTransform,
      null,
    )
  }

  fun runStreamingLines(
    command: List<String>,
    input: String,
    workingDirectory: Path,
    timeoutSeconds: Int,
    environmentTransform: (MutableMap<String, String>) -> Unit,
    stopAfterLine: (String) -> Boolean,
  ): ProcessResult = runInternal(
    command,
    input,
    workingDirectory,
    timeoutSeconds,
    environmentTransform,
    stopAfterLine,
  )

  private fun runInternal(
    command: List<String>,
    input: String,
    workingDirectory: Path,
    timeoutSeconds: Int,
    environmentTransform: (MutableMap<String, String>) -> Unit,
    stopAfterLine: ((String) -> Boolean)?,
  ): ProcessResult {
    val processBuilder = ProcessBuilder(command)
      .directory(workingDirectory.toFile())
      .redirectErrorStream(false)
    environmentTransform(processBuilder.environment())
    val process = processBuilder.start()
    val readers = Executors.newFixedThreadPool(2)
    val terminatedEarly = AtomicBoolean(false)
    return try {
      val stdout = readers.submit<String> {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
          if (stopAfterLine == null) {
            reader.readText()
          } else {
            buildString {
              while (true) {
                val line = reader.readLine() ?: break
                append(line).append('\n')
                if (stopAfterLine(line) && terminatedEarly.compareAndSet(false, true)) {
                  process.destroy()
                  break
                }
              }
            }
          }
        }
      }
      val stderr = readers.submit<String> {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
      }
      process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
        writer.write(input)
        writer.flush()
      }
      val finished = process.waitFor(timeoutSeconds.coerceIn(2, 120).toLong(), TimeUnit.SECONDS)
      if (!finished) process.destroyForcibly()
      ProcessResult(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = stdout.get(3, TimeUnit.SECONDS),
        stderr = stderr.get(3, TimeUnit.SECONDS),
        timedOut = !finished,
        terminatedEarly = terminatedEarly.get(),
      )
    } catch (interrupted: InterruptedException) {
      process.destroyForcibly()
      Thread.currentThread().interrupt()
      throw interrupted
    } finally {
      if (process.isAlive) process.destroyForcibly()
      readers.shutdownNow()
    }
  }
}

object BillingEnvironment {
  private val claudeAutocompleteEnvironment = mapOf(
    "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1",
    "DISABLE_AUTOUPDATER" to "1",
    "MAX_THINKING_TOKENS" to "0",
  )
  private val claudeBilledKeys = setOf(
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_BEDROCK_BASE_URL",
    "ANTHROPIC_CUSTOM_HEADERS",
    "ANTHROPIC_FOUNDRY_API_KEY",
    "ANTHROPIC_FOUNDRY_BASE_URL",
    "ANTHROPIC_VERTEX_BASE_URL",
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_BEARER_TOKEN_BEDROCK",
    "CLAUDE_CODE_USE_BEDROCK",
    "CLAUDE_CODE_USE_VERTEX",
    "CLAUDE_CODE_USE_FOUNDRY",
    "CLAUDE_CODE_USE_MANTLE",
    "GOOGLE_APPLICATION_CREDENTIALS",
  )
  private val codexBilledKeys = setOf(
    "OPENAI_API_KEY",
    "OPENAI_BASE_URL",
    "AZURE_OPENAI_API_KEY",
    "AZURE_OPENAI_ENDPOINT",
    "CODEX_API_KEY",
    "OPENAI_ORG_ID",
    "OPENAI_PROJECT_ID",
  )

  fun subscriptionOnlyClaude(environment: MutableMap<String, String>) {
    claudeBilledKeys.forEach(environment::remove)
    environment.putAll(claudeAutocompleteEnvironment)
  }

  fun subscriptionOnlyCodex(environment: MutableMap<String, String>) {
    codexBilledKeys.forEach(environment::remove)
  }
}
