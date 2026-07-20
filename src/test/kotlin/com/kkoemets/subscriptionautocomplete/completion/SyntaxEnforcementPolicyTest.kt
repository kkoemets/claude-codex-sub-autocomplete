package com.kkoemets.subscriptionautocomplete.completion

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxEnforcementPolicyTest {
  private val identity = SyntaxParserIdentity(
    productCode = "IU",
    build = "IU-261.1",
    fileType = "TypeScript",
    parserClass = "example.TypeScriptParserDefinition",
  )
  private val rule = SyntaxEnforcementRule(
    productCode = identity.productCode,
    build = identity.build,
    fileType = identity.fileType,
    parserClass = identity.parserClass,
    categories = setOf("REFERENCE_EXPRESSION", "STATEMENT"),
  )

  @Test
  fun `current policy is shadow only until evidence is installed`() {
    assertFalse(
      CompletionValidationPolicy.isEnforceable(
        identity,
        listOf(error("REFERENCE_EXPRESSION")),
      ),
    )
  }

  @Test
  fun `evidence policy requires an exact identity and allowed categories`() {
    val policy = VersionedSyntaxEnforcementPolicy(version = 7, rules = setOf(rule))

    assertTrue(policy.allows(identity, listOf(error("REFERENCE_EXPRESSION"), error("STATEMENT"))))
    assertFalse(policy.allows(identity.copy(build = "IU-261.2"), listOf(error("REFERENCE_EXPRESSION"))))
    assertFalse(policy.allows(identity.copy(productCode = "IC"), listOf(error("REFERENCE_EXPRESSION"))))
    assertFalse(policy.allows(identity.copy(fileType = "JavaScript"), listOf(error("REFERENCE_EXPRESSION"))))
    assertFalse(policy.allows(identity.copy(parserClass = "example.OtherParser"), listOf(error("REFERENCE_EXPRESSION"))))
    assertFalse(policy.allows(identity, listOf(error("UNLABELLED_CATEGORY"))))
    assertFalse(policy.allows(identity, emptyList()))
  }

  private fun error(category: String) = SyntaxErrorFingerprint(10, 11, category)
}
