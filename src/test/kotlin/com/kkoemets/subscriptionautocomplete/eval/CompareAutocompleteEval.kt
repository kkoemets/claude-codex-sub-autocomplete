package com.kkoemets.subscriptionautocomplete.eval

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

object CompareAutocompleteEval {
  @JvmStatic
  fun main(args: Array<String>) {
    val baselinePath = requiredPath("eval.baseline")
    val candidatePath = requiredPath("eval.candidate")
    val gson = Gson()
    val baseline = Files.newBufferedReader(baselinePath).use {
      gson.fromJson(it, EvalRunReport::class.java)
    }
    val candidate = Files.newBufferedReader(candidatePath).use {
      gson.fromJson(it, EvalRunReport::class.java)
    }
    val comparison = EvalBaselineComparator.compare(baseline, candidate)
    println(GsonBuilder().setPrettyPrinting().create().toJson(comparison))
    check(comparison.compatible) { "Reports are not comparable: ${comparison.reason}" }
  }

  private fun requiredPath(property: String): Path {
    val value = System.getProperty(property).orEmpty()
    require(value.isNotBlank()) { "Missing -P${property.removePrefix("eval.").replace('.', '_')}" }
    return Path.of(value).also { require(Files.isRegularFile(it)) { "Report not found: $it" } }
  }
}
