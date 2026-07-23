package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class TerminalLiveSuite(
  val id: String,
  val minimumPasses: Int,
  val targetPasses: Int,
  val caseIds: List<String>,
)

object TerminalLiveSuiteLoader {
  const val RESOURCE = "terminal/live-suite-v1.json"
  const val ID = "terminal-live-v1"
  const val CASES = 50

  fun load(dataset: TerminalEvalDataset, resource: String = RESOURCE): TerminalLiveSuite {
    val classpathStream = javaClass.classLoader.getResourceAsStream(resource)
    val fixturePath = Path.of("src/test/testData").resolve(resource)
    val reader = classpathStream?.bufferedReader(StandardCharsets.UTF_8)
      ?: Files.newBufferedReader(fixturePath, StandardCharsets.UTF_8)
    val suite = reader.use { Gson().fromJson(it, TerminalLiveSuite::class.java) }
    val byId = dataset.cases.associateBy(TerminalEvalCase::id)
    require(suite.id == ID) { "Unsupported terminal live suite: ${suite.id}" }
    require(suite.caseIds.size == CASES && suite.caseIds.distinct().size == CASES) {
      "Terminal live suite must contain exactly $CASES unique cases"
    }
    require(suite.minimumPasses == 45 && suite.targetPasses == 46) {
      "Terminal live suite thresholds must remain 45/50 minimum and 46/50 target"
    }
    require(suite.caseIds.all(byId::containsKey)) { "Terminal live suite contains unknown case IDs" }
    val cases = suite.caseIds.map(byId::getValue)
    val categoryCounts = cases.groupingBy(TerminalEvalCase::category).eachCount()
    require(categoryCounts.size == 20 && categoryCounts.values.all { it >= 2 }) {
      "Terminal live suite must represent all 20 categories with at least two cases each"
    }
    require(cases.all { it.shell in SUPPORTED_LIVE_SHELLS }) {
      "Official terminal live cases must have executable local syntax checks"
    }
    return suite
  }

  fun cases(dataset: TerminalEvalDataset): List<TerminalEvalCase> {
    val byId = dataset.cases.associateBy(TerminalEvalCase::id)
    return load(dataset).caseIds.map(byId::getValue)
  }

  private val SUPPORTED_LIVE_SHELLS = setOf("bash", "sh", "zsh")
}
