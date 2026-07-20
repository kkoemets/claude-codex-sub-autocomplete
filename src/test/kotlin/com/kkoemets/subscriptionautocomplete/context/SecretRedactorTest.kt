package com.kkoemets.subscriptionautocomplete.context

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretRedactorTest {
  @Test
  fun `redacts embedded authorization header before generic structured values`() {
    val redacted = SecretRedactor.redact("curl with Authorization: Bearer secret-value")

    assertFalse(redacted.contains("secret-value"))
    assertTrue(redacted.contains("<redacted>"))
  }

  @Test
  fun `redacts environment and structured secrets`() {
    val redacted = SecretRedactor.redact(
      """
        API_TOKEN=abc123
        password: hunter2
        normal: visible
        endpoint: https://alice:secret@example.com/api
      """.trimIndent(),
    )

    assertFalse(redacted.contains("abc123"))
    assertFalse(redacted.contains("hunter2"))
    assertFalse(redacted.contains("alice:secret"))
    assertTrue(redacted.contains("normal: visible"))
  }

  @Test
  fun `redacts provider tokens authorization headers JWTs and private keys`() {
    val aws = "AKIAIOSFODNN7EXAMPLE"
    val github = "ghp_123456789012345678901234567890123456"
    val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature1234567890"
    val redacted = SecretRedactor.redact(
      """
        Authorization: Bearer bearer-secret-value
        aws = $aws
        github = $github
        jwt = $jwt
        -----BEGIN PRIVATE KEY-----
        private-material
        -----END PRIVATE KEY-----
      """.trimIndent(),
    )

    assertFalse(redacted.contains("bearer-secret-value"))
    assertFalse(redacted.contains(aws))
    assertFalse(redacted.contains(github))
    assertFalse(redacted.contains(jwt))
    assertFalse(redacted.contains("private-material"))
  }
}
