package com.kkoemets.subscriptionautocomplete.context

import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test
  fun `redacts credentials in any URL scheme and common provider formats`() {
    val secrets = mapOf(
      "DATABASE_URL=postgres://admin:hunter2@db.internal:5432/app" to "hunter2",
      "url: jdbc:mysql://root:s3cr3tpass@localhost/db" to "s3cr3tpass",
      "STRIPE=$FAKE_STRIPE_KEY" to FAKE_STRIPE_KEY,
      "MAPS=AIzaSyA1234567890abcdefghijklmnopqrstuv" to "AIzaSyA1234567890abcdefghijklmnopqrstuv",
      "temporary = ASIAIOSFODNN7EXAMPLE" to "ASIAIOSFODNN7EXAMPLE",
      "notify(\"https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXX\")" to "XXXXXXXXXXXX",
      "bot = xoxb-1234567890-abcdefghij" to "xoxb-1234567890-abcdefghij",
      "PRIVATE_KEY=abc123def456" to "abc123def456",
      "client_key: 'quoted secret value'" to "quoted secret value",
      "headers = {\"Authorization\": \"token gho_abcdefghijklmnopqrstuv\"}" to "gho_abcdefghijklmnopqrstuv",
    )
    secrets.forEach { (line, secret) ->
      val redacted = SecretRedactor.redact(line)
      assertFalse(redacted.contains(secret), "Leaked in: $redacted")
    }
  }

  @Test
  fun `ordinary code before the caret survives unchanged`() {
    listOf(
      "  val maxTokens = settings.max",
      "fun login(password: String, token: String): Session {",
      "if (token == null) return",
      "import org.springframework.transaction.annotation.Transactional",
      "val token = tokenProvider.current()",
      "password = getpass.getpass()",
      "api_key = os.environ[\"API_KEY\"]",
      "secret: process.env.SECRET,",
      "password = ",
      "token = None",
      "authorization: String?,",
      "val cookieJar = CookieJar.NO_COOKIES",
      "std::string token::value() const;",
      "monkey = keyboard.layout()",
    ).forEach { line ->
      assertEquals(line, SecretRedactor.redact(line), "Code was rewritten")
    }
  }

  @Test
  fun `an unquoted value with spaces is still treated as a secret`() {
    // A YAML plain scalar: hiding a harmless expression is the safer mistake.
    assertFalse(SecretRedactor.redact("password: correct horse battery staple").contains("horse"))
  }

  @Test
  fun `a key block cut off by the context window is still removed`() {
    val body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKj"
    val beforeCaret = "val pem = \"\"\"\n-----BEGIN RSA PRIVATE KEY-----\n$body\nMIIEvQ"
    val afterCaret = "IBADANBgkq\n$body\n-----END RSA PRIVATE KEY-----\n\"\"\"\nval next = 1"

    val redactedBefore = SecretRedactor.redact(beforeCaret)
    val redactedAfter = SecretRedactor.redact(afterCaret)

    assertFalse(redactedBefore.contains(body))
    assertTrue(redactedBefore.startsWith("val pem = "))
    assertFalse(redactedAfter.contains(body))
    assertTrue(redactedAfter.endsWith("val next = 1"))
  }

  @Test
  fun `a key body without its delimiters is removed line by line`() {
    val body = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cKj"

    val redacted = SecretRedactor.redact("$body\n$body\nend")

    assertFalse(redacted.contains(body))
    assertTrue(redacted.endsWith("end"))
  }

  private companion object {
    /** Assembled at runtime so the source holds no secret-shaped literal for secret scanners to flag. */
    val FAKE_STRIPE_KEY = listOf("sk", "live", "0000fake0000test0000").joinToString("_")
  }
}
