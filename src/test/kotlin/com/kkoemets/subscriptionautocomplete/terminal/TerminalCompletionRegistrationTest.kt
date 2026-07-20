package com.kkoemets.subscriptionautocomplete.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionRegistrationTest {
  @Test
  fun `terminal action is allowed inside the reworked terminal`() {
    assertEquals(
      listOf("SubscriptionAutocomplete.GenerateTerminalCommand"),
      TerminalCompletionAllowedActionsProvider().getActionIds(),
    )
  }
}
