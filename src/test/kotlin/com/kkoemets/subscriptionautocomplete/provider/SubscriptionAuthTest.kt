package com.kkoemets.subscriptionautocomplete.provider

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNull

class SubscriptionAuthTest {
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
}
