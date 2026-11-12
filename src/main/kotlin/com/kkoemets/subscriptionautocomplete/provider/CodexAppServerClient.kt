package com.kkoemets.subscriptionautocomplete.provider

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kkoemets.subscriptionautocomplete.completion.CompletionOutputEnvelope
import com.kkoemets.subscriptionautocomplete.completion.CompletionPrompt
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

internal class CodexAppServerClient : AutoCloseable {
  private val sessionLock = ReentrantLock()
  private val turnSlots = Semaphore(MAX_CONCURRENT_TURNS, true)
  private val unavailableUntil = HashMap<Path, Long>()
  private val availableSessions = ArrayDeque<Session>()
  private val allSessions = LinkedHashSet<Session>()
  private var closed = false

  fun complete(
    executable: Path,
    prompt: CompletionPrompt,
    model: String,
    effort: String,
    timeoutSeconds: Int,
    maxOutputTokens: Int,
  ): BackendResult {
    val deadline = CompletionDeadline.afterSeconds(timeoutSeconds)
    val acquired = turnSlots.tryAcquire(
      deadline.remainingMillis("waiting for an app-server turn slot"),
      TimeUnit.MILLISECONDS,
    )
    if (!acquired) return BackendResult.Failure("Codex completion timed out while waiting for a turn slot.")
    var activeSession: Session? = null
    return try {
      activeSession = borrowSession(executable, deadline)
      activeSession.complete(prompt, model, effort, maxOutputTokens, deadline)
    } catch (timeout: CodexPhaseTimeoutException) {
      BackendResult.Failure("Codex completion timed out during ${timeout.phase}.")
    } finally {
      activeSession?.let(::returnSession)
      turnSlots.release()
    }
  }

  override fun close() {
    sessionLock.lock()
    try {
      closed = true
      allSessions.toList().forEach(Session::close)
      allSessions.clear()
      availableSessions.clear()
      unavailableUntil.clear()
    } finally {
      sessionLock.unlock()
    }
  }

  private fun borrowSession(executable: Path, deadline: CompletionDeadline): Session {
    sessionLock.lockInterruptibly()
    try {
      // After shutdown nothing would ever stop a newly started app-server.
      if (closed) throw CodexAppServerUnavailableException("Codex app-server client is closed.")
      deadline.remainingMillis("app-server startup")
      while (availableSessions.isNotEmpty()) {
        val candidate = availableSessions.removeFirst()
        if (candidate.executable == executable && candidate.isAlive) return candidate
        allSessions.remove(candidate)
        candidate.close()
      }
      val now = System.nanoTime()
      unavailableUntil[executable]
        ?.takeIf { it > now }
        ?.let { throw CodexAppServerUnavailableException("Codex app-server is in fallback cooldown.") }
      unavailableUntil.remove(executable)
      return try {
        Session.start(executable, deadline).also(allSessions::add)
      } catch (unavailable: CodexAppServerUnavailableException) {
        unavailableUntil[executable] = now + APP_SERVER_RETRY_NANOS
        throw unavailable
      }
    } finally {
      sessionLock.unlock()
    }
  }

  private fun returnSession(session: Session) {
    sessionLock.lock()
    try {
      if (session.isAlive) {
        availableSessions.addLast(session)
      } else {
        allSessions.remove(session)
        session.close()
      }
    } finally {
      sessionLock.unlock()
    }
  }

  private class Session private constructor(
    val executable: Path,
    private val workspace: Path,
    private val process: Process,
    private val writer: BufferedWriter,
  ) : AutoCloseable {
    private val requestSequence = AtomicLong()
    private val pendingRequests = HashMap<Long, CompletableFuture<JsonObject>>()
    private val turnRegistry = TurnRegistry()
    private val writeLock = Any()
    private val stateLock = Any()
    private val stderrLines = ArrayDeque<String>()
    @Volatile
    private var readerAlive = true

    val isAlive: Boolean
      get() = process.isAlive && readerAlive

    init {
      startStdoutReader()
      startStderrReader()
    }

    fun initialize(deadline: CompletionDeadline) {
      val response = request(
        "initialize",
        JsonObject().apply {
          add("clientInfo", JsonObject().apply {
            addProperty("name", "subscription_autocomplete_intellij")
            addProperty("title", "Claude/Codex Sub Autocomplete")
            addProperty("version", "0.5.7")
          })
        },
        deadline,
        "app-server initialization",
      )
      requireResult(response, "Codex app-server initialization failed")
      notify("initialized", JsonObject())
    }

    fun complete(
      prompt: CompletionPrompt,
      model: String,
      effort: String,
      maxOutputTokens: Int,
      deadline: CompletionDeadline,
    ): BackendResult {
      val providerStartedAt = System.nanoTime()
      var threadId: String? = null
      var turnId: String? = null
      var accumulator: TurnAccumulator? = null
      var upstreamTerminal = false
      try {
        val threadResponse = request(
          "thread/start",
          CodexAppServerProtocol.threadStart(workspace, prompt, model, effort, maxOutputTokens),
          deadline,
          "thread startup",
        )
        val threadResult = requireResult(threadResponse, "Codex could not start an autocomplete thread")
        threadId = threadResult.getAsJsonObject("thread")?.get("id")?.asString
          ?: throw CodexAppServerException("Codex app-server returned no thread ID.")
        val turnResponse = request(
          "turn/start",
          CodexAppServerProtocol.turnStart(threadId, prompt.userPrompt, model, effort),
          deadline,
          "turn startup",
        )
        val turnResult = requireResult(turnResponse, "Codex could not start an autocomplete turn")
        turnId = turnResult.getAsJsonObject("turn")?.get("id")?.asString
          ?: throw CodexAppServerException("Codex app-server returned no turn ID.")
        accumulator = TurnAccumulator(CompletionOutputEnvelope.maxCharacters(maxOutputTokens))
        turnRegistry.register(turnId, accumulator)
        val completion = accumulator.ready.get(
          deadline.remainingMillis("model generation"),
          TimeUnit.MILLISECONDS,
        )
        upstreamTerminal = completion.serverTerminal
        return when (completion.status) {
          "completed" -> accumulator.text().takeIf(String::isNotBlank)
            ?.let {
              BackendResult.Success(
                it,
                model,
                "resident app-server",
                providerTiming(accumulator, providerStartedAt),
              )
            }
            ?: BackendResult.Failure("Codex returned no completion.")
          "output_limit" -> {
            if (!completion.serverTerminal) {
              upstreamTerminal = interruptAndAwait(threadId, turnId, accumulator)
            }
            BackendResult.Failure(
              completion.error ?: CompletionOutputEnvelope.failureMessage(
                "Codex",
                accumulator.observedCharacters(),
                accumulator.maxCharacters(),
              ),
            )
          }
          "interrupted" -> BackendResult.Failure("Codex completion was interrupted.")
          else -> BackendResult.Failure(completion.error ?: "Codex completion failed.")
        }
      } catch (_: TimeoutException) {
        upstreamTerminal = interruptAndAwait(threadId, turnId, accumulator)
        throw CodexPhaseTimeoutException("model generation")
      } catch (timeout: CodexPhaseTimeoutException) {
        upstreamTerminal = interruptAndAwait(threadId, turnId, accumulator)
        throw timeout
      } catch (interrupted: InterruptedException) {
        upstreamTerminal = interruptAndAwait(threadId, turnId, accumulator)
        throw interrupted
      } finally {
        if (turnId != null) turnRegistry.forget(turnId, ignoreLateEvents = !upstreamTerminal)
        if (threadId != null) archive(threadId)
        if (!upstreamTerminal) close()
      }
    }

    private fun providerTiming(accumulator: TurnAccumulator, startedAtNanos: Long): ProviderTiming = ProviderTiming(
      firstTokenMillis = accumulator.firstTokenMillis(startedAtNanos),
      totalMillis = (System.nanoTime() - startedAtNanos) / 1_000_000,
    )

    override fun close() {
      // Stop the whole tree first. Behind a `codex.cmd` launcher the app-server is a grandchild that
      // outlives its parent, and closing the writer can block while a stuck child holds the pipe.
      ProcessRunner.terminateProcessTree(process)
      runCatching { writer.close() }
      failPending(CodexAppServerException("Codex app-server stopped."))
      TemporaryWorkspace.delete(workspace)
    }

    private fun request(
      method: String,
      params: JsonObject,
      deadline: CompletionDeadline,
      phase: String,
    ): JsonObject {
      val id = requestSequence.incrementAndGet()
      val future = CompletableFuture<JsonObject>()
      synchronized(stateLock) { pendingRequests[id] = future }
      return try {
        write(CodexAppServerProtocol.request(id, method, params))
        future.get(deadline.remainingMillis(phase), TimeUnit.MILLISECONDS)
      } catch (timeout: TimeoutException) {
        throw CodexPhaseTimeoutException(phase)
      } catch (execution: ExecutionException) {
        throw CodexAppServerException(execution.cause?.message ?: execution.message.orEmpty())
      } finally {
        synchronized(stateLock) { pendingRequests.remove(id) }
      }
    }

    private fun notify(method: String, params: JsonObject) {
      write(CodexAppServerProtocol.notification(method, params))
    }

    private fun interruptAndAwait(
      threadId: String?,
      turnId: String?,
      accumulator: TurnAccumulator?,
    ): Boolean {
      if (threadId == null || turnId == null || accumulator == null || !process.isAlive) return false
      val cleanupDeadline = CompletionDeadline.afterMillis(CLEANUP_TIMEOUT_MILLIS)
      return try {
        val response = request(
          "turn/interrupt",
          CodexAppServerProtocol.turnInterrupt(threadId, turnId),
          cleanupDeadline,
          "turn interruption",
        )
        requireResult(response, "Codex could not interrupt autocomplete turn")
        accumulator.terminal.get(
          cleanupDeadline.remainingMillis("turn cleanup"),
          TimeUnit.MILLISECONDS,
        )
        true
      } catch (_: Exception) {
        false
      }
    }

    private fun archive(threadId: String) {
      if (!process.isAlive) return
      val cleanupDeadline = CompletionDeadline.afterMillis(CLEANUP_TIMEOUT_MILLIS)
      runCatching {
        val response = request(
          "thread/archive",
          CodexAppServerProtocol.threadArchive(threadId),
          cleanupDeadline,
          "thread archival",
        )
        requireResult(response, "Codex could not archive autocomplete thread")
      }
      terminateLeakedRuntimeWorkers()
    }

    private fun terminateLeakedRuntimeWorkers() {
      val workers = process.toHandle().descendants()
        .filter { handle ->
          // Windows reports no command line for other processes, only the executable path.
          handle.info().commandLine().or { handle.info().command() }.orElse("")
            .replace('\\', '/')
            .contains("/cua_node/bin/node_repl", ignoreCase = true)
        }
        .toList()
      workers.forEach { worker ->
        worker.destroy()
        runCatching { worker.onExit().get(WORKER_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS) }
        if (worker.isAlive) worker.destroyForcibly()
      }
    }

    private fun write(message: JsonObject) {
      synchronized(writeLock) {
        if (!process.isAlive) throw unavailable("Codex app-server exited before receiving a request")
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
      }
    }

    private fun startStdoutReader() {
      Thread({
        try {
          process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach(::handleLine)
          }
          readerAlive = false
          failPending(unavailable("Codex app-server closed its output stream"))
        } catch (error: Exception) {
          readerAlive = false
          failPending(error)
        }
      }, "subscription-autocomplete-codex-stdout").apply {
        isDaemon = true
        start()
      }
    }

    private fun startStderrReader() {
      Thread({
        runCatching {
          process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
              synchronized(stderrLines) {
                stderrLines.addLast(line)
                while (stderrLines.size > MAX_STDERR_LINES) stderrLines.removeFirst()
              }
            }
          }
        }
      }, "subscription-autocomplete-codex-stderr").apply {
        isDaemon = true
        start()
      }
    }

    /** One unexpected message shape must not stop the reader and, with it, the whole session. */
    private fun handleLine(line: String) {
      try {
        val message = JsonParser.parseString(line).takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return
        val method = message.string("method")
        val id = message.get("id")?.takeUnless { it.isJsonNull }
        when {
          method != null && id != null -> rejectServerRequest(id, method)
          id != null -> runCatching { id.asLong }.getOrNull()?.let { requestId ->
            synchronized(stateLock) { pendingRequests[requestId] }?.complete(message)
          }
          method == "item/agentMessage/delta" -> {
            val params = message.objectOrNull("params") ?: return
            val turnId = params.string("turnId") ?: return
            turnRegistry.append(turnId, params.string("delta").orEmpty())
          }
          method == "turn/completed" -> completeTurn(message.objectOrNull("params"))
        }
      } catch (_: RuntimeException) {
        // Not a message this client understands.
      }
    }

    private fun completeTurn(params: JsonObject?) {
      val turn = params?.objectOrNull("turn") ?: return
      val turnId = turn.string("id") ?: return
      val status = turn.string("status") ?: "failed"
      val error = turn.objectOrNull("error")?.string("message")
      turnRegistry.complete(turnId, CodexAppServerProtocol.finalAgentText(turn), status, error)
    }

    private fun rejectServerRequest(id: JsonElement, method: String) {
      write(JsonObject().apply {
        add("id", id.deepCopy())
        add("error", JsonObject().apply {
          addProperty("code", -32601)
          addProperty("message", "Autocomplete client does not support server request: $method")
        })
      })
    }

    private fun requireResult(response: JsonObject, prefix: String): JsonObject {
      response.getAsJsonObject("error")?.let { error ->
        val message = error.get("message")?.asString ?: error.toString()
        throw CodexAppServerException("$prefix: $message")
      }
      return response.getAsJsonObject("result")
        ?: throw CodexAppServerException("$prefix: missing result.")
    }

    private fun failPending(error: Throwable) {
      val futures = synchronized(stateLock) {
        pendingRequests.values.toList().also { pendingRequests.clear() }
      }
      turnRegistry.failAll(error)
      futures.forEach { it.completeExceptionally(error) }
    }

    private fun unavailable(message: String): CodexAppServerUnavailableException {
      val stderr = synchronized(stderrLines) { stderrLines.toList().takeLast(4).joinToString("\n") }
      return CodexAppServerUnavailableException(
        if (stderr.isBlank()) message else "$message: ${stderr.take(500)}",
      )
    }

    companion object {
      fun start(executable: Path, deadline: CompletionDeadline): Session {
        val workspace = Files.createTempDirectory("subscription-autocomplete-codex-server-")
        try {
          val process = ProcessRunner.start(
            CodexAppServerProtocol.appServerCommand(executable),
            workspace,
            BillingEnvironment::subscriptionOnlyCodex,
          )
          val session = Session(
            executable,
            workspace,
            process,
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8),
          )
          return try {
            session.initialize(deadline)
            session
          } catch (timeout: CodexPhaseTimeoutException) {
            session.close()
            throw timeout
          } catch (interrupted: InterruptedException) {
            // The request was cancelled. That says nothing about the app-server's health, so it must
            // not start the fallback cooldown or a one-shot run for a request nobody is waiting for.
            session.close()
            throw interrupted
          } catch (error: Exception) {
            session.close()
            throw CodexAppServerUnavailableException(error.message ?: "Codex app-server is unavailable.")
          }
        } catch (timeout: CodexPhaseTimeoutException) {
          TemporaryWorkspace.delete(workspace)
          throw timeout
        } catch (interrupted: InterruptedException) {
          TemporaryWorkspace.delete(workspace)
          Thread.currentThread().interrupt()
          throw interrupted
        } catch (error: Exception) {
          TemporaryWorkspace.delete(workspace)
          if (error is CodexAppServerUnavailableException) throw error
          throw CodexAppServerUnavailableException(error.message ?: "Codex app-server could not start.")
        }
      }

      private const val MAX_STDERR_LINES = 20
    }
  }

  private companion object {
    const val MAX_CONCURRENT_TURNS = 2
    const val CLEANUP_TIMEOUT_MILLIS = 1_500L
    const val WORKER_SHUTDOWN_MILLIS = 250L
    const val APP_SERVER_RETRY_NANOS = 300_000_000_000L
  }
}

/**
 * Routes turn events from the reader thread to the request that owns the turn.
 *
 * The turn ID only becomes known to the requesting thread when it has parsed the `turn/start`
 * response, while the reader thread is already processing the lines that follow it. Events for a
 * turn that is not registered yet are therefore kept and replayed on registration; dropping them
 * lost the start of a completion, or its terminal event, and left the request to time out.
 */
internal class TurnRegistry(private val maxIgnoredTurns: Int = 128) {
  private sealed interface EarlyEvent {
    data class Delta(val text: String) : EarlyEvent
    data class Completed(val finalText: String, val status: String, val error: String?) : EarlyEvent
  }

  private val active = HashMap<String, TurnAccumulator>()
  private val ignored = LinkedHashSet<String>()
  private val early = LinkedHashMap<String, MutableList<EarlyEvent>>()

  @Synchronized
  fun register(turnId: String, accumulator: TurnAccumulator) {
    ignored.remove(turnId)
    active[turnId] = accumulator
    early.remove(turnId)?.forEach { event ->
      when (event) {
        is EarlyEvent.Delta -> accumulator.append(event.text)
        is EarlyEvent.Completed -> accumulator.completeServer(event.finalText, event.status, event.error)
      }
    }
  }

  @Synchronized
  fun append(turnId: String, delta: String): Boolean {
    active[turnId]?.let { return it.append(delta) }
    if (turnId !in ignored) keepEarly(turnId, EarlyEvent.Delta(delta))
    return false
  }

  @Synchronized
  fun complete(turnId: String, finalText: String, status: String, error: String?): Boolean {
    if (ignored.remove(turnId)) return false
    active[turnId]?.let { return it.completeServer(finalText, status, error) }
    keepEarly(turnId, EarlyEvent.Completed(finalText, status, error))
    return false
  }

  @Synchronized
  fun forget(turnId: String, ignoreLateEvents: Boolean) {
    active.remove(turnId)
    early.remove(turnId)
    if (!ignoreLateEvents) return
    ignored.add(turnId)
    while (ignored.size > maxIgnoredTurns.coerceAtLeast(1)) {
      ignored.remove(ignored.first())
    }
  }

  @Synchronized
  fun failAll(error: Throwable) {
    active.values.forEach { it.fail(error) }
    active.clear()
    ignored.clear()
    early.clear()
  }

  @Synchronized
  fun activeCount(): Int = active.size

  /** Bounded: an event for a turn that is never registered must not accumulate. */
  private fun keepEarly(turnId: String, event: EarlyEvent) {
    val events = early.getOrPut(turnId) { mutableListOf() }
    if (events.size < MAX_EARLY_EVENTS_PER_TURN) events += event
    while (early.size > MAX_EARLY_TURNS) early.remove(early.keys.first())
  }

  private companion object {
    const val MAX_EARLY_TURNS = 4
    const val MAX_EARLY_EVENTS_PER_TURN = 256
  }
}

internal class TurnAccumulator(private val maxCharacters: Int) {
  private val builder = StringBuilder()
  private var observedCharacters = 0
  private var firstDeltaAtNanos: Long? = null
  val ready = CompletableFuture<TurnCompletion>()
  val terminal = CompletableFuture<TurnCompletion>()

  @Synchronized
  fun append(delta: String): Boolean {
    if (ready.isDone || delta.isEmpty()) return false
    if (firstDeltaAtNanos == null) firstDeltaAtNanos = System.nanoTime()
    observedCharacters += delta.length
    val limit = maxCharacters.coerceAtLeast(1)
    val remaining = (limit - builder.length).coerceAtLeast(0)
    builder.append(delta.take(remaining))
    if (observedCharacters <= limit) return false
    ready.complete(
      TurnCompletion(
        "output_limit",
        CompletionOutputEnvelope.failureMessage("Codex", observedCharacters, limit),
        serverTerminal = false,
      ),
    )
    return true
  }

  @Synchronized
  fun completeServer(finalText: String, status: String, error: String?): Boolean {
    val limit = maxCharacters.coerceAtLeast(1)
    val upstreamCompletion = TurnCompletion(status, error, serverTerminal = true)
    val readyCompletion = if (finalText.length > limit) {
      observedCharacters = maxOf(observedCharacters, finalText.length)
      TurnCompletion(
        "output_limit",
        CompletionOutputEnvelope.failureMessage("Codex", finalText.length, limit),
        serverTerminal = true,
      )
    } else {
      upstreamCompletion
    }
    if (!ready.isDone && finalText.isNotBlank()) {
      builder.clear()
      builder.append(finalText.take(limit))
      observedCharacters = maxOf(observedCharacters, finalText.length)
    }
    terminal.complete(upstreamCompletion)
    ready.complete(readyCompletion)
    return true
  }

  @Synchronized
  fun fail(error: Throwable) {
    val completion = TurnCompletion("failed", error.message, serverTerminal = true)
    terminal.complete(completion)
    ready.complete(completion)
  }

  @Synchronized
  fun text(): String = builder.toString()

  @Synchronized
  fun observedCharacters(): Int = observedCharacters

  fun maxCharacters(): Int = maxCharacters.coerceAtLeast(1)

  @Synchronized
  fun firstTokenMillis(startedAtNanos: Long): Long? =
    firstDeltaAtNanos?.let { (it - startedAtNanos).coerceAtLeast(0) / 1_000_000 }
}

internal data class TurnCompletion(
  val status: String,
  val error: String?,
  val serverTerminal: Boolean,
)

internal class CompletionDeadline private constructor(private val expiresAtNanos: Long) {
  fun remainingMillis(phase: String): Long {
    val remaining = expiresAtNanos - System.nanoTime()
    if (remaining <= 0) throw CodexPhaseTimeoutException(phase)
    return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)
  }

  companion object {
    fun afterSeconds(seconds: Int): CompletionDeadline = afterMillis(seconds.coerceIn(2, 120) * 1_000L)

    fun afterMillis(milliseconds: Long): CompletionDeadline =
      CompletionDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds.coerceAtLeast(1)))
  }
}

internal object CodexAppServerProtocol {
  private val DISABLED_FEATURES = listOf(
    "apps",
    "browser_use",
    "code_mode",
    "code_mode_host",
    "computer_use",
    "in_app_browser",
    "multi_agent",
    "plugins",
    "shell_tool",
    "tool_suggest",
    "unified_exec",
    "workspace_dependencies",
  )

  fun appServerCommand(executable: Path): List<String> = buildList {
    add(executable.toString())
    add("app-server")
    add("--stdio")
    add("--config")
    add("mcp_servers={}")
    DISABLED_FEATURES.forEach { feature ->
      add("--disable")
      add(feature)
    }
  }

  fun request(id: Long, method: String, params: JsonObject): JsonObject = JsonObject().apply {
    addProperty("method", method)
    addProperty("id", id)
    add("params", params)
  }

  fun notification(method: String, params: JsonObject): JsonObject = JsonObject().apply {
    addProperty("method", method)
    add("params", params)
  }

  fun threadStart(
    workspace: Path,
    prompt: CompletionPrompt,
    model: String,
    effort: String,
    maxOutputTokens: Int,
  ): JsonObject = JsonObject().apply {
    addProperty("model", model)
    addProperty("cwd", workspace.toAbsolutePath().normalize().toString())
    addProperty("approvalPolicy", "never")
    addProperty("sandbox", "read-only")
    addProperty("ephemeral", true)
    addProperty("personality", "none")
    addProperty("baseInstructions", prompt.systemPrompt)
    addProperty(
      "developerInstructions",
      "Use at most $maxOutputTokens approximate tokens. " +
        "Never call tools, inspect files, or execute commands.",
    )
    add("config", JsonObject().apply {
      addProperty("model_reasoning_effort", effort)
      add("mcp_servers", JsonObject())
      add("features", JsonObject().apply {
        DISABLED_FEATURES.forEach { feature -> addProperty(feature, false) }
      })
    })
  }

  fun turnStart(threadId: String, text: String, model: String, effort: String): JsonObject = JsonObject().apply {
    addProperty("threadId", threadId)
    addProperty("model", model)
    addProperty("effort", effort)
    addProperty("approvalPolicy", "never")
    add("input", JsonArray().apply {
      add(JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
      })
    })
  }

  fun turnInterrupt(threadId: String, turnId: String): JsonObject = JsonObject().apply {
    addProperty("threadId", threadId)
    addProperty("turnId", turnId)
  }

  fun threadArchive(threadId: String): JsonObject = JsonObject().apply {
    addProperty("threadId", threadId)
  }

  fun finalAgentText(turn: JsonObject): String = turn.get("items")
    ?.takeIf(JsonElement::isJsonArray)
    ?.asJsonArray
    ?.asSequence()
    ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    ?.filter { item -> item.string("type") in setOf("agentMessage", "agent_message") }
    ?.mapNotNull { item -> item.string("text") }
    ?.lastOrNull()
    .orEmpty()
}

internal open class CodexAppServerException(message: String) : Exception(message)

internal class CodexAppServerUnavailableException(message: String) : CodexAppServerException(message)

internal class CodexPhaseTimeoutException(val phase: String) : CodexAppServerException(phase)

private fun JsonObject.string(name: String): String? =
  get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.objectOrNull(name: String): JsonObject? =
  get(name)?.takeIf(JsonElement::isJsonObject)?.asJsonObject
