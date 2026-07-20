package com.kkoemets.subscriptionautocomplete.provider

import com.intellij.openapi.Disposable

class ProviderSessionLifecycle : Disposable {
  override fun dispose() {
    BackendRegistry.shutdown()
  }
}
