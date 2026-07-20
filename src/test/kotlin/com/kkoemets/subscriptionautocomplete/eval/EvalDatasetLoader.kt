package com.kkoemets.subscriptionautocomplete.eval

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

object EvalDatasetLoader {
  const val RESOURCE = "autocomplete/corpus-v1.json"
  const val VERSION = "autocomplete-corpus-v1"

  fun load(resource: String = RESOURCE): EvalDataset {
    val classpathStream = javaClass.classLoader.getResourceAsStream(resource)
    val fixturePath = Path.of("src/test/testData").resolve(resource)
    val reader = classpathStream?.bufferedReader(StandardCharsets.UTF_8)
      ?: Files.newBufferedReader(fixturePath, StandardCharsets.UTF_8)
    val definition = reader.use {
      Gson().fromJson(reader, CorpusDefinition::class.java)
    }
    return validate(definition.toDataset())
  }

  internal fun validate(dataset: EvalDataset): EvalDataset {
    require(dataset.version == VERSION) { "Unsupported corpus version: ${dataset.version}" }
    require(dataset.cases.size == EXPECTED_CASES) {
      "Expected $EXPECTED_CASES cases, got ${dataset.cases.size}"
    }
    require(dataset.cases.map(EvalCase::id).distinct().size == dataset.cases.size) {
      "Evaluation case IDs must be unique"
    }
    val surfaces = dataset.cases.map(EvalCase::surface).distinct()
    require(surfaces.size == EXPECTED_SURFACES) {
      "Expected $EXPECTED_SURFACES surfaces, got ${surfaces.size}"
    }
    surfaces.forEach { surface ->
      val cases = dataset.casesFor(surface)
      require(cases.size == CASES_PER_SURFACE) {
        "Surface $surface must contain $CASES_PER_SURFACE cases"
      }
      require(cases.count { it.kind == EvalTaskKind.MASKED_SPAN } == 3) {
        "Surface $surface must contain three masked-span cases"
      }
      require(cases.count { it.kind == EvalTaskKind.ORDINARY } == 3) {
        "Surface $surface must contain three ordinary cases"
      }
      require(cases.count { it.kind == EvalTaskKind.NEGATIVE } == 2) {
        "Surface $surface must contain two negative cases"
      }
      require(cases.count { it.kind == EvalTaskKind.RECENT_CURRENT_CONTEXT } == 1) {
        "Surface $surface must contain one recent-current-context case"
      }
    }
    dataset.cases.forEach(::validateCase)
    require(dataset.taskCounts() == EXPECTED_TASK_COUNTS) {
      "Unexpected task counts: ${dataset.taskCounts()}"
    }
    return dataset
  }

  private fun validateCase(case: EvalCase) {
    require(case.id.matches(Regex("[a-z0-9-]+"))) { "Invalid case ID: ${case.id}" }
    require(case.surface.matches(Regex("[a-z0-9-]+"))) { "Invalid surface: ${case.surface}" }
    require(case.input.fileName.isNotBlank()) { "${case.id}: missing file name" }
    require('/' !in case.input.fileName && '\\' !in case.input.fileName) {
      "${case.id}: fileName must not contain a path"
    }
    require(case.tags.isNotEmpty() && case.tags.all(KNOWN_TAGS::contains)) {
      "${case.id}: unknown or missing tags ${case.tags - KNOWN_TAGS}"
    }
    require("<CURSOR>" !in case.input.visibleText()) {
      "${case.id}: input fields must not contain cursor control markers"
    }
    when (case.kind) {
      EvalTaskKind.NEGATIVE -> {
        require(case.oracle.reference.isEmpty()) { "${case.id}: negative case has a reference" }
        require(case.oracle.expectedGroups.isEmpty()) { "${case.id}: negative case has semantic targets" }
        require(case.oracle.negativeExpectation != null) { "${case.id}: missing negative expectation" }
      }
      EvalTaskKind.RECENT_CURRENT_CONTEXT -> {
        require(case.input.fragments.any { it.source == "recent-current" }) {
          "${case.id}: recent-current case has no recent current-content fragment"
        }
        validatePositive(case)
      }
      else -> validatePositive(case)
    }
    EvalLeakGuard.requireOracleAbsent(case)
  }

  private fun validatePositive(case: EvalCase) {
    require(case.oracle.reference.isNotEmpty()) { "${case.id}: missing reference" }
    require(case.oracle.negativeExpectation == null) { "${case.id}: positive case has negative expectation" }
    require(case.oracle.maxCharacters in 1..4_096) { "${case.id}: invalid output limit" }
  }

  private data class CorpusDefinition(
    val version: String,
    val surfaces: List<SurfaceDefinition>,
  ) {
    fun toDataset(): EvalDataset = EvalDataset(
      version,
      surfaces.flatMap { surface ->
        surface.cases.map { definition -> definition.toCase(surface) }
      },
    )
  }

  private data class SurfaceDefinition(
    val id: String,
    val languageId: String,
    val fileName: String,
    val cases: List<CaseDefinition>,
  )

  private data class CaseDefinition(
    val id: String,
    val kind: String,
    val prefix: String,
    val suffix: String?,
    val target: String?,
    val typed: String?,
    val tags: List<String>?,
    val expectedGroups: List<List<String>>?,
    val negativeExpectation: String? = null,
    val maxCharacters: Int,
    val fragments: List<FragmentDefinition>?,
  ) {
    fun toCase(surface: SurfaceDefinition): EvalCase = EvalCase(
      id = "${surface.id}-$id",
      surface = surface.id,
      kind = EvalTaskKind.valueOf(kind),
      tags = tags.orEmpty().toSet(),
      input = EvalInput(
        languageId = surface.languageId,
        fileName = surface.fileName,
        prefix = prefix,
        suffix = suffix.orEmpty(),
        typed = typed.orEmpty(),
        fragments = fragments.orEmpty().map(FragmentDefinition::toFragment),
      ),
      oracle = EvalOracle(
        reference = target.orEmpty(),
        expectedGroups = expectedGroups.orEmpty(),
        negativeExpectation = negativeExpectation?.let(NegativeExpectation::valueOf),
        maxCharacters = maxCharacters.takeIf { it > 0 } ?: 160,
      ),
    )
  }

  private data class FragmentDefinition(
    val label: String,
    val content: String,
    val source: String?,
  ) {
    fun toFragment(): EvalContextFragment = EvalContextFragment(
      label = label,
      content = content,
      source = source ?: "test",
    )
  }

  private const val EXPECTED_CASES = 99
  private const val EXPECTED_SURFACES = 11
  private const val CASES_PER_SURFACE = 9
  private val EXPECTED_TASK_COUNTS = mapOf(
    EvalTaskKind.MASKED_SPAN to 33,
    EvalTaskKind.ORDINARY to 33,
    EvalTaskKind.NEGATIVE to 22,
    EvalTaskKind.RECENT_CURRENT_CONTEXT to 11,
  )
  private val KNOWN_TAGS = setOf(
    "single-line",
    "suffix-sensitive",
    "multiline",
    "locally-implied",
    "context-dependent",
    "ambiguous",
    "no-backend",
    "no-render",
    "recent-current",
    "comment",
    "structured-scalar",
    "privacy",
  )
}

object EvalLeakGuard {
  fun requireOracleAbsent(case: EvalCase) {
    val reference = leakSensitiveReference(case.oracle.reference)
    if (reference.isEmpty()) return
    require(reference !in normalize(case.input.visibleText())) {
      "${case.id}: oracle target leaked into provider-visible input"
    }
  }

  fun requireOracleAbsent(case: EvalCase, providerPrompt: String) {
    val reference = leakSensitiveReference(case.oracle.reference)
    if (reference.isEmpty()) return
    require(reference !in normalize(providerPrompt)) {
      "${case.id}: oracle target leaked into provider prompt"
    }
  }

  private fun leakSensitiveReference(value: String): String = normalize(value).trim()
    .takeIf { it.length >= MIN_LEAK_SENSITIVE_CHARACTERS }
    .orEmpty()

  private fun normalize(value: String): String = value.replace("\r\n", "\n")

  // Short identifiers and scalars may legitimately occur in type declarations or the right-hand context.
  // Longer exact spans are sufficiently distinctive to identify accidental oracle injection.
  private const val MIN_LEAK_SENSITIVE_CHARACTERS = 8
}
