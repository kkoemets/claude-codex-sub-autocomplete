package com.kkoemets.subscriptionautocomplete.context

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

class SemanticContextCollectorTest {
  @Test
  fun `named PSI elements without text ranges are ignored`() {
    val namedWithoutRange = proxy(PsiNamedElement::class.java) { methodName ->
      when (methodName) {
        "getName" -> "synthetic"
        "getTextRange" -> null
        "getParent" -> null
        else -> null
      }
    }
    val leaf = proxy(PsiElement::class.java) { methodName ->
      if (methodName == "getParent") namedWithoutRange else null
    }

    assertTrue(collectEnclosingDeclarations(leaf, 0).isEmpty())
  }

  private fun <T> proxy(type: Class<T>, value: (String) -> Any?): T = type.cast(
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> value(method.name) },
  )
}
