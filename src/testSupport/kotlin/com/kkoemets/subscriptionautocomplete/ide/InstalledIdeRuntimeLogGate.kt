package com.kkoemets.subscriptionautocomplete.ide

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

data class IdeRuntimeError(val file: Path, val line: Int, val text: String) {
  fun summary(): String = "$file:$line: ${text.lineSequence().firstOrNull().orEmpty()}"
}

data class IdeRuntimeLogScan(
  val scannedFiles: List<Path>,
  val pluginErrors: List<IdeRuntimeError>,
  val unrelatedErrors: List<IdeRuntimeError>,
  val acceptedPlatformErrors: List<IdeRuntimeError> = emptyList(),
) {
  fun requireNoBlockingErrors() {
    check(pluginErrors.isEmpty() && unrelatedErrors.isEmpty()) {
      "Installed IDE runtime errors (${pluginErrors.size} plugin, ${unrelatedErrors.size} unrelated records/stacktraces):\n" +
        (pluginErrors.map { "Plugin IDE error: ${it.summary()}" } +
          unrelatedErrors.map { "Unrelated IDE error: ${it.summary()}" }).joinToString("\n")
    }
  }
}

/** Test support shared by the headless unit suite and installed-IDE fixtures. */
object InstalledIdeRuntimeLogGate {
  private const val LEVELS = "TRACE|DEBUG|INFO|WARN|WARNING|ERROR|SEVERE|FATAL"
  private val header = Regex(
    "^\\s*(?:\\d{4}-\\d{2}-\\d{2}[ T]\\S+\\s+(?:\\[[^]]*]\\s*)?)?" +
      "(?:\\[($LEVELS)]\\s*|($LEVELS)\\s*(?:-|:)\\s*)",
  )
  private val pluginPackage = Regex("(?<![\\w.])com\\.kkoemets\\.subscriptionautocomplete(?:\\b|\\.)")
  private val pluginBlame = Regex("Plugin to blame:\\s*Claude/Codex Sub Autocomplete\\b", RegexOption.IGNORE_CASE)
  private val abbreviatedPluginLogger = Regex("^#c\\.k\\.s\\.[\\w.$]+\\s*(?:-|:)")
  private val errorLevels = setOf("ERROR", "SEVERE", "FATAL")
  // DiagnosticsLog deliberately uses WARN with an explicit error-level payload.
  // Match only a leading payload label, not a quoted label later in a message.
  private val declaredError = Regex("^(?:#[\\w.$]+\\s*(?:-|:)\\s*)?\\[(ERROR|SEVERE|FATAL)](?:\\s|$)")
  private val jvmFatalBanner = Regex("^\\s*# A fatal error has been detected by the Java Runtime Environment:")
  private val uncaughtException = Regex("^\\s*Exception in thread \"[^\"]+\" [\\w.$]+(?:Exception|Error)(?::|\\s|$)")
  private val logName = Regex(".*\\.log(?:\\.\\d+)?(?:\\.gz)?")

  fun scan(logDirectory: Path, expectedIdeBuild: String? = null): IdeRuntimeLogScan {
    val mainLog = logDirectory.resolve("idea.log")
    check(Files.isRegularFile(mainLog) && Files.size(mainLog) > 0) {
      "Missing or empty installed-IDE runtime log: $mainLog"
    }
    val files = Files.walk(logDirectory).use { paths ->
      paths.filter { Files.isRegularFile(it) && isEvidenceFile(it) }.sorted().toList()
    }
    val errors = files.flatMap { file ->
      val text = read(file)
      if (file.fileName.toString() == "stacktrace.txt") {
        check(text.isNotBlank()) { "Empty saved IDE stacktrace: $file" }
        // The message may contain explicit plugin blame even when the saved
        // stack only contains platform frames.
        val message = file.resolveSibling("message.txt")
        val evidence = text + if (Files.isRegularFile(message)) "\n${read(message)}" else ""
        listOf(IdeRuntimeError(file, 1, evidence))
      } else errorRecords(file, text)
    }
    val (plugin, unrelated) = errors.partition { attributableToPlugin(it.text) }
    val accepted = KnownAndroidTerminalError.acceptedRecords(mainLog, read(mainLog), expectedIdeBuild, unrelated)
    return IdeRuntimeLogScan(files, plugin, unrelated - accepted.toSet(), accepted)
  }

  /**
   * The caller must include IDE shutdown/join in runAndClose. Its finally block
   * finishes before scanning, including when a fixture returns early or fails.
   */
  fun <T> afterIdeShutdown(
    logDirectory: Path,
    report: (String) -> Unit = ::println,
    expectedIdeBuild: String? = null,
    runAndClose: () -> T,
  ): T {
    var originalFailure: Throwable? = null
    try {
      return runAndClose()
    } catch (failure: Throwable) {
      originalFailure = failure
      throw failure
    } finally {
      try {
        val scan = scan(logDirectory, expectedIdeBuild)
        report("Installed IDE runtime log scan: ${scan.scannedFiles.size} files; " +
          "${scan.pluginErrors.size} plugin errors; ${scan.unrelatedErrors.size} unrelated blocking IDE errors; " +
          "${scan.acceptedPlatformErrors.size} accepted stock-IDE records/stacktraces")
        scan.unrelatedErrors.forEach { report("Unrelated IDE error: ${it.summary()}") }
        scan.acceptedPlatformErrors.forEach { report("Accepted stock Android terminal error: ${it.summary()}") }
        scan.requireNoBlockingErrors()
      } catch (logFailure: Throwable) {
        if (originalFailure == null) throw logFailure
        if (originalFailure !== logFailure) originalFailure.addSuppressed(logFailure)
      }
    }
  }

  private fun isEvidenceFile(file: Path): Boolean {
    val name = file.fileName.toString()
    return logName.matches(name) || name == "stacktrace.txt" || name in setOf("stderr.txt", "stdout.txt")
  }

  private fun read(file: Path): String = if (file.fileName.toString().endsWith(".gz")) {
    GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { it.readText() }
  } else Files.readString(file)

  private fun attributableToPlugin(text: String): Boolean {
    if (pluginPackage.containsMatchIn(text) || pluginBlame.containsMatchIn(text)) return true
    // IDEA shortens package names in the logger field. Match that field only:
    // another logger's message can quote the same abbreviated class name.
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val recordHeader = header.find(firstLine) ?: return false
    return abbreviatedPluginLogger.containsMatchIn(firstLine.substring(recordHeader.range.last + 1))
  }

  private fun errorRecords(file: Path, text: String): List<IdeRuntimeError> {
    val errors = mutableListOf<IdeRuntimeError>()
    var level: String? = null
    var firstLine = 1
    val record = StringBuilder()
    fun finishRecord() {
      if (level in errorLevels) errors += IdeRuntimeError(file, firstLine, record.toString().trimEnd())
      record.setLength(0)
    }
    text.lineSequence().forEachIndexed { index, line ->
      val match = header.find(line)
      if (match != null) {
        finishRecord()
        val payloadError = declaredError.find(line.substring(match.range.last + 1))
        level = payloadError?.groupValues?.get(1) ?: match.groupValues[1].ifEmpty { match.groupValues[2] }
        firstLine = index + 1
      } else if (jvmFatalBanner.containsMatchIn(line) || uncaughtException.containsMatchIn(line)) {
        // Native crash reports and uncaught stderr exceptions have no logger
        // header. Start a separate record so earlier INFO cannot supply blame.
        finishRecord()
        level = "FATAL"
        firstLine = index + 1
      }
      record.appendLine(line)
    }
    finishRecord()
    return errors
  }
}
