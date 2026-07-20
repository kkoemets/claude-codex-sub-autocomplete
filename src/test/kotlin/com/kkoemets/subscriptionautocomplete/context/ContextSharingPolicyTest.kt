package com.kkoemets.subscriptionautocomplete.context

import com.kkoemets.subscriptionautocomplete.completion.CompletionDestination
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContextSharingPolicyTest {
  @Test
  fun `subscription and bundled consent are independent`() {
    val settings = AutocompleteSettings.SettingsState(
      allowCrossFileForSubscription = true,
      allowCrossFileForBundledFim = false,
    )

    val subscription = ContextSharingPolicy.from(CompletionDestination.SUBSCRIPTION_PROCESS, settings)
    val bundled = ContextSharingPolicy.from(CompletionDestination.BUNDLED_LOCAL_PROCESS, settings)

    assertTrue(subscription.allowCrossFile)
    assertFalse(bundled.allowCrossFile)
    assertNotEquals(subscription.revision, bundled.revision)
  }
}
