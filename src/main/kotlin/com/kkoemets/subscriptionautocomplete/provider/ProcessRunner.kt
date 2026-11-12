package com.kkoemets.subscriptionautocomplete.provider

import com.intellij.execution.CommandLineUtil
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
  ): ProcessResult = runInternal(command, input, workingDirectory, timeoutSeconds, environmentTransform, null)

  fun runStreamingLines(
    command: List<String>,
    input: String,
    workingDirectory: Path,
    timeoutSeconds: Int,
    environmentTransform: (MutableMap<String, String>) -> Unit,
    stopAfterLine: (String) -> Boolean,
  ): ProcessResult =
    runInternal(command, input, workingDirectory, timeoutSeconds, environmentTransform, stopAfterLine)

  /**
   * Starts a provider CLI. Every provider process must be created here.
   *
   * `ProcessBuilder` does not escape arguments on Windows: an embedded double quote is silently
   * dropped by the child's argument parser, and `.cmd`/`.bat` launchers add `cmd.exe` parsing on top.
   * The platform's [CommandLineUtil] applies the escaping IntelliJ itself uses for both launcher
   * kinds and leaves the command untouched on other systems.
   */
  internal fun start(
    command: List<String>,
    workingDirectory: Path,
    environmentTransform: (MutableMap<String, String>) -> Unit,
  ): Process {
    require(command.isNotEmpty()) { "Provider command is empty" }
    val processBuilder = ProcessBuilder(CommandLineUtil.toCommandLine(command))
      .directory(workingDirectory.toFile())
      .redirectErrorStream(false)
    environmentTransform(processBuilder.environment())
    return processBuilder.start()
  }

  private fun runInternal(
    command: List<String>,
    input: String,
    workingDirectory: Path,
    timeoutSeconds: Int,
    environmentTransform: (MutableMap<String, String>) -> Unit,
    stopAfterLine: ((String) -> Boolean)?,
  ): ProcessResult {
    val process = start(command, workingDirectory, environmentTransform)
    val terminatedEarly = AtomicBoolean(false)
    val stdout = StreamCollector("stdout", process.inputStream) { line ->
      if (stopAfterLine != null && stopAfterLine(line) && terminatedEarly.compareAndSet(false, true)) {
        terminateProcessTree(process)
        true
      } else {
        false
      }
    }
    val stderr = StreamCollector("stderr", process.errorStream)
    return try {
      writeInput(process, input)
      val finished = process.waitFor(timeoutSeconds.coerceIn(2, 120).toLong(), TimeUnit.SECONDS)
      if (!finished) terminateProcessTree(process)
      ProcessResult(
        exitCode = if (finished) process.exitValue() else -1,
        stdout = stdout.await(),
        stderr = stderr.await(),
        timedOut = !finished,
        terminatedEarly = terminatedEarly.get(),
      )
    } catch (interrupted: InterruptedException) {
      terminateProcessTree(process)
      Thread.currentThread().interrupt()
      throw interrupted
    } finally {
      if (process.isAlive) terminateProcessTree(process)
    }
  }

  /**
   * A CLI that rejects its arguments exits without reading standard input. The resulting broken
   * pipe must not hide the error the CLI printed, so the caller still collects its output.
   */
  private fun writeInput(process: Process, input: String) {
    try {
      process.outputStream.use { stream ->
        stream.write(input.toByteArray(StandardCharsets.UTF_8))
        stream.flush()
      }
    } catch (_: IOException) {
      // The exit code and standard error describe the failure.
    }
  }

  internal fun terminateProcessTree(process: Process) {
    // Capture children before stopping the parent, otherwise they may be reparented.
    val descendants = runCatching { process.descendants().use { it.toList() } }.getOrDefault(emptyList())
    descendants.asReversed().forEach(ProcessHandle::destroyForcibly)
    process.destroyForcibly()
    // Windows keeps the working directory locked until the process has really exited.
    try {
      process.waitFor(EXIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  /** Drains one output stream on a daemon thread so a full pipe can never block the child. */
  private class StreamCollector(
    name: String,
    stream: InputStream,
    private val stopAfterLine: ((String) -> Boolean)? = null,
  ) {
    private val text = StringBuffer()
    private val reader = thread(isDaemon = true, name = "subscription-autocomplete-$name") {
      try {
        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
          if (stopAfterLine == null) {
            val buffer = CharArray(8192)
            while (true) {
              val read = reader.read(buffer)
              if (read < 0) break
              text.append(buffer, 0, read)
            }
          } else {
            while (true) {
              val line = reader.readLine() ?: break
              text.append(line).append('\n')
              if (stopAfterLine(line)) break
            }
          }
        }
      } catch (_: IOException) {
        // The stream closes when the process is terminated; keep what was read.
      }
    }

    /** Returns what was read, even when an orphaned grandchild still holds the pipe open. */
    fun await(): String {
      try {
        reader.join(DRAIN_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      return text.toString()
    }
  }

  private const val EXIT_GRACE_MILLIS = 2_000L
  private const val DRAIN_MILLIS = 3_000L
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
    removeIgnoringCase(environment, claudeBilledKeys)
    environment.putAll(claudeAutocompleteEnvironment)
  }

  fun subscriptionOnlyCodex(environment: MutableMap<String, String>) {
    removeIgnoringCase(environment, codexBilledKeys)
  }

  /**
   * Windows environment names are case-insensitive, so `anthropic_api_key` reaches the CLI as a
   * billed credential there even though it is a different key in a POSIX environment map.
   */
  private fun removeIgnoringCase(environment: MutableMap<String, String>, keys: Set<String>) {
    environment.keys.removeIf { name -> keys.any { it.equals(name, ignoreCase = true) } }
  }
}
