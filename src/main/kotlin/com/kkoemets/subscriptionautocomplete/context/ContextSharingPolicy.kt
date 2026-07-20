package com.kkoemets.subscriptionautocomplete.context

import com.kkoemets.subscriptionautocomplete.completion.CompletionDestination
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings

data class ContextSharingPolicy(
  val destination: CompletionDestination,
  val allowCrossFile: Boolean,
) {
  val revision: String = "${destination.name}:$allowCrossFile"

  companion object {
    fun from(
      destination: CompletionDestination,
      settings: AutocompleteSettings.SettingsState,
    ): ContextSharingPolicy = ContextSharingPolicy(
      destination = destination,
      allowCrossFile = when (destination) {
        CompletionDestination.SUBSCRIPTION_PROCESS -> settings.allowCrossFileForSubscription
        CompletionDestination.BUNDLED_LOCAL_PROCESS -> settings.allowCrossFileForBundledFim
      },
    )
  }
}
