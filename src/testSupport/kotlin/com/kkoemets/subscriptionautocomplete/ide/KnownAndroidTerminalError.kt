package com.kkoemets.subscriptionautocomplete.ide

import java.nio.file.Path
import java.security.MessageDigest

/** A stock Reworked-terminal/C++ caret-listener defect, reproduced with the plugin absent.
 * The exception expires on a build/runtime/stack change; unmatched records remain blocking.
 */
internal object KnownAndroidTerminalError {
  const val BUILD = "AI-261.26222.65.2614.16204760"
  private val startup = Regex(
    """(?m)^\d{4}-\d{2}-\d{2} [\d:,]+\s+\[\s*\d+]\s+INFO - #c\.i\.p\.i\.b\.AppStarter - IDE: Android Studio \(build #(AI-[\d.]+), [^\r\n]+\)$""",
  )
  private val header = Regex(
    """^\d{4}-\d{2}-\d{2} [\d:,]+\s+\[\s*(\d+)]\s+SEVERE - #c\.i\.u\.EventDispatcher - """,
  )
  private val generatedProxy = Regex("""\${'$'}Proxy\d+""")
  private val metadata = listOf(
    "Android Studio Quail 4 | 2026.1.4  Build #$BUILD",
    "JDK: 25.0.3; VM: OpenJDK 64-Bit Server VM; Vendor: JetBrains s.r.o.",
    "OS: Mac OS X",
  )

  fun acceptedRecords(
    mainLog: Path,
    mainLogText: String,
    expectedBuild: String?,
    nonPluginErrors: List<IdeRuntimeError>,
  ): List<IdeRuntimeError> {
    if (expectedBuild != BUILD ||
      startup.findAll(mainLogText).map { it.groupValues[1] }.toList() != listOf(BUILD)) return emptyList()
    val records = nonPluginErrors.filter { it.file == mainLog }
    val bundles = records.windowed(5).filter { bundle ->
      val bodies = bundle.map { record ->
        val match = header.find(record.text) ?: return@filter false
        record.text.substring(match.range.last + 1)
      }
      val ticks = bundle.map { header.find(it.text)!!.groupValues[1].toLong() }
      digest(bodies[0]) == "611e79879b01dfa0c9f277f37a81d08e6faffe59bfafda3c0aaea48cdbd41da7" &&
        bodies.subList(1, 4) == metadata &&
        bodies[4] in setOf("Last Action:", "Last Action: InsertInlineCompletionAction") &&
        ticks.zipWithNext().all { (a, b) -> b >= a } && ticks.last() - ticks.first() <= 100 &&
        bundle.zipWithNext().all { (a, b) -> a.line + a.text.lineSequence().count() == b.line }
    }
    val saved = nonPluginErrors.filter { record ->
      record.file.fileName.toString() == "stacktrace.txt" &&
        record.file.parent.parent == mainLog.parent.resolve("errors") &&
        Regex("error-\\d+").matches(record.file.parent.fileName.toString()) &&
        digest(record.text) == "5dbe84002fa2ccef84bde4f7c5621836fd11f0e27b251f5a41c52072c1c35596"
    }
    // Pair complete logged incidents with saved traces. Orphans, extra copies, plugin
    // blame, causes/suppressed exceptions and changes to any frame cannot disappear.
    val count = minOf(bundles.size, saved.size)
    return bundles.take(count).flatten() + saved.take(count)
  }

  private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(generatedProxy.replace(text.trimEnd()) { "\$Proxy" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
}
