package com.kkoemets.subscriptionautocomplete.completion

internal data class SyntaxParserIdentity(
  val productCode: String,
  val build: String,
  val fileType: String,
  val parserClass: String,
)

internal data class SyntaxEnforcementRule(
  val productCode: String,
  val build: String,
  val fileType: String,
  val parserClass: String,
  val categories: Set<String>,
) {
  init {
    require(productCode.isNotBlank())
    require(build.isNotBlank())
    require(fileType.isNotBlank())
    require(parserClass.isNotBlank())
    require(categories.isNotEmpty() && categories.none(String::isBlank))
  }

  fun matches(identity: SyntaxParserIdentity): Boolean =
    productCode == identity.productCode &&
      build == identity.build &&
      fileType == identity.fileType &&
      parserClass == identity.parserClass
}

internal data class VersionedSyntaxEnforcementPolicy(
  val version: Int,
  val rules: Set<SyntaxEnforcementRule>,
) {
  init {
    require(version > 0)
  }

  fun allows(
    identity: SyntaxParserIdentity,
    errors: List<SyntaxErrorFingerprint>,
  ): Boolean {
    if (errors.isEmpty()) return false
    return rules.any { rule ->
      rule.matches(identity) && errors.all { error -> error.category in rule.categories }
    }
  }
}

internal object CompletionValidationPolicy {
  const val VERSION = 1

  // Enforcement stays shadow-only until labelled evidence is recorded for an exact
  // product, build, file type, parser implementation, and parser-error category.
  private val current = VersionedSyntaxEnforcementPolicy(VERSION, emptySet())

  fun isEnforceable(
    identity: SyntaxParserIdentity,
    errors: List<SyntaxErrorFingerprint>,
  ): Boolean = current.allows(identity, errors)
}
