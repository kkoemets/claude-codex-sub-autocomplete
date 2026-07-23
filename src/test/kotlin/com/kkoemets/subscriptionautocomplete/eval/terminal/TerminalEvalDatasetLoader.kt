package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object TerminalEvalDatasetLoader {
  const val RESOURCE = "terminal/corpus-v1.json"
  const val VERSION = "terminal-positive-corpus-v1"
  const val EXPECTED_CASES = 200
  const val EXPECTED_CATEGORIES = 20

  fun load(resource: String = RESOURCE): TerminalEvalDataset {
    val classpathStream = javaClass.classLoader.getResourceAsStream(resource)
    val fixturePath = Path.of("src/test/testData").resolve(resource)
    val reader = classpathStream?.bufferedReader(StandardCharsets.UTF_8)
      ?: Files.newBufferedReader(fixturePath, StandardCharsets.UTF_8)
    val definition = reader.use { Gson().fromJson(it, CorpusDefinition::class.java) }
    return validate(definition.toDataset())
  }

  internal fun validate(dataset: TerminalEvalDataset): TerminalEvalDataset {
    require(dataset.version == VERSION) { "Unsupported terminal corpus version: ${dataset.version}" }
    require(dataset.cases.size == EXPECTED_CASES) {
      "Expected $EXPECTED_CASES terminal cases, got ${dataset.cases.size}"
    }
    require(dataset.cases.map(TerminalEvalCase::id).distinct().size == dataset.cases.size) {
      "Terminal case IDs must be unique"
    }
    val categories = dataset.cases.map(TerminalEvalCase::category).distinct()
    require(categories.size == EXPECTED_CATEGORIES) {
      "Expected $EXPECTED_CATEGORIES terminal categories, got ${categories.size}"
    }
    categories.forEach { category ->
      require(dataset.casesForCategory(category).size == CASES_PER_CATEGORY) {
        "Terminal category $category must contain $CASES_PER_CATEGORY cases"
      }
    }
    dataset.cases.forEach(::validateCase)
    return dataset
  }

  private fun validateCase(case: TerminalEvalCase) {
    require(case.id.matches(Regex("[a-z0-9-]+"))) { "Invalid terminal case ID: ${case.id}" }
    require(case.category.matches(Regex("[a-z0-9-]+"))) { "Invalid terminal category: ${case.category}" }
    require(case.request.startsWith("# ") && case.description.length >= 3) {
      "${case.id}: request must be a realistic # prompt"
    }
    require(case.request.none { it == '\n' || it == '\r' || it.isISOControl() }) {
      "${case.id}: request must fit on one physical line"
    }
    require(case.reference.isNotBlank() && case.reference.none { it == '\n' || it == '\r' }) {
      "${case.id}: reference must be one nonblank physical line"
    }
    require(case.shell.isNotBlank()) { "${case.id}: missing shell" }
    require(case.platform in KNOWN_PLATFORMS) { "${case.id}: unknown platform ${case.platform}" }
    require(case.projectName.isNotBlank()) { "${case.id}: missing project name" }
    require(case.requiredGroups.isNotEmpty() && case.requiredGroups.all { group ->
      group.isNotEmpty() && group.all(String::isNotBlank)
    }) { "${case.id}: semantic requirements must contain nonblank alternatives" }
    require(case.forbiddenFragments.all(String::isNotBlank)) {
      "${case.id}: forbidden fragments must be nonblank"
    }
    require(case.validator == null || case.validator in KNOWN_VALIDATORS) {
      "${case.id}: unknown named validator ${case.validator}"
    }
  }

  private data class CorpusDefinition(
    val version: String,
    val categories: List<CategoryDefinition>,
  ) {
    fun toDataset(): TerminalEvalDataset = TerminalEvalDataset(
      version = version,
      cases = categories.flatMap { category -> category.cases.map { it.toCase(category) } },
    )
  }

  private data class CategoryDefinition(
    val id: String,
    val shell: String?,
    val platform: String?,
    val projectName: String?,
    val projectMarkers: List<String>?,
    val cases: List<CaseDefinition>,
  )

  private data class CaseDefinition(
    val id: String,
    val request: String,
    val reference: String,
    val shell: String?,
    val platform: String?,
    val projectName: String?,
    val projectMarkers: List<String>?,
    val requiredGroups: List<List<String>>,
    val forbiddenFragments: List<String>?,
    val validator: String?,
  ) {
    fun toCase(category: CategoryDefinition): TerminalEvalCase = TerminalEvalCase(
      id = id,
      category = category.id,
      request = request,
      reference = reference,
      shell = shell ?: category.shell ?: "zsh",
      platform = platform ?: category.platform ?: "linux",
      projectName = projectName ?: category.projectName ?: "sample-project",
      projectMarkers = projectMarkers ?: category.projectMarkers.orEmpty(),
      requiredGroups = requiredGroups,
      forbiddenFragments = forbiddenFragments.orEmpty(),
      validator = validator,
    )
  }

  private const val CASES_PER_CATEGORY = 10
  private val KNOWN_PLATFORMS = setOf("linux", "macos", "windows")
  private val KNOWN_VALIDATORS = setOf(
    "git-immediate-child-repos-master",
    "git-restore-file-from-head",
    "rsync-local-dist-to-app-server",
    "delete-immediate-child-builds",
    "recreate-local-dist",
  )
}
