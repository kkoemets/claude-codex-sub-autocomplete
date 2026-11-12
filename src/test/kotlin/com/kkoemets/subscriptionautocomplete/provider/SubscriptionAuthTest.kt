package com.kkoemets.subscriptionautocomplete.provider

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubscriptionAuthTest {
  @Test
  fun `accepts a Claude subscription login`() {
    assertNull(SubscriptionAuth.claudeAuthError(result(stdout = CLAUDE_SUBSCRIPTION)))
  }

  @Test
  fun `a signed-out Claude CLI gets login guidance whatever its exit code`() {
    val signedOut = """{"loggedIn":false,"authMethod":"none","apiProvider":"firstParty"}"""
    for (exitCode in listOf(0, 1)) {
      assertContains(
        SubscriptionAuth.claudeAuthError(result(stdout = signedOut, exitCode = exitCode)).orEmpty(),
        "claude auth login",
      )
    }
  }

  @Test
  fun `Claude API key and cloud provider logins are rejected as non-subscription`() {
    listOf(
      """{"loggedIn":true,"authMethod":"api_key","apiProvider":"firstParty"}""",
      """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"bedrock"}""",
      """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"vertex"}""",
    ).forEach { status ->
      assertContains(SubscriptionAuth.claudeAuthError(result(stdout = status)).orEmpty(), "Claude subscription")
    }
  }

  @Test
  fun `a Claude launcher that cannot start is not reported as signed out`() {
    val error = SubscriptionAuth.claudeAuthError(
      result(stderr = "'node' is not recognized as an internal or external command,\noperable program or batch file.", exitCode = 1),
    ).orEmpty()

    assertContains(error, "Could not run `claude auth status`")
    assertContains(error, "operable program or batch file")
    assertFalse("claude auth login" in error, error)
  }

  @Test
  fun `a slow Claude status check is reported as a timeout`() {
    val error = SubscriptionAuth.claudeAuthError(result(exitCode = -1, timedOut = true)).orEmpty()

    assertContains(error, "did not answer")
    assertFalse("not signed in" in error, error)
  }

  @Test
  fun `unexpected Claude status shapes do not throw`() {
    listOf("[]", "{\"loggedIn\":{}}", "{\"loggedIn\":true,\"authMethod\":[]}", "", "null").forEach { status ->
      assertNotNull(SubscriptionAuth.claudeAuthError(result(stdout = status)), status)
    }
  }

  @Test
  fun `accepts ChatGPT login reported on stdout`() {
    val error = SubscriptionAuth.codexAuthError(result(stdout = "Logged in using ChatGPT"))

    assertNull(error)
  }

  @Test
  fun `accepts ChatGPT login reported on stderr after a warning`() {
    val error = SubscriptionAuth.codexAuthError(
      result(stderr = "WARNING: PATH aliases unavailable\nLogged in using ChatGPT"),
    )

    assertNull(error)
  }

  @Test
  fun `checks negative status before the logged in substring`() {
    val error = SubscriptionAuth.codexAuthError(result(stdout = "Not logged in"))

    assertContains(error.orEmpty(), "must be signed in")
  }

  @Test
  fun `rejects API key authentication`() {
    val error = SubscriptionAuth.codexAuthError(result(stdout = "Logged in using API key"))

    assertContains(error.orEmpty(), "API-key authentication instead")
  }

  @Test
  fun `reports a failed status command`() {
    val error = SubscriptionAuth.codexAuthError(result(exitCode = 7, stderr = "failed"))

    assertContains(error.orEmpty(), "exited with code 7")
  }

  private fun result(
    exitCode: Int = 0,
    stdout: String = "",
    stderr: String = "",
    timedOut: Boolean = false,
  ): ProcessResult = ProcessResult(exitCode, stdout, stderr, timedOut)

  private companion object {
    const val CLAUDE_SUBSCRIPTION = """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty"}"""
  }
}
