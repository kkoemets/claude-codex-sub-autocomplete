package com.kkoemets.subscriptionautocomplete.eval

import com.kkoemets.subscriptionautocomplete.context.CompletionContext
import com.kkoemets.subscriptionautocomplete.context.ContextBudgeter
import com.kkoemets.subscriptionautocomplete.context.ContextFragment
import com.kkoemets.subscriptionautocomplete.context.ContextFragmentSource
import com.kkoemets.subscriptionautocomplete.context.TextBudget

enum class EvalTaskKind {
  MASKED_SPAN,
  ORDINARY,
  NEGATIVE,
  RECENT_CURRENT_CONTEXT,
}

enum class NegativeExpectation {
  NO_BACKEND_CALL,
  NO_RENDERED_SUGGESTION,
}

data class EvalContextFragment(
  val label: String,
  val content: String,
  val source: String = "test",
)

data class EvalInput(
  val languageId: String,
  val fileName: String,
  val prefix: String,
  val suffix: String,
  val typed: String = "",
  val fragments: List<EvalContextFragment> = emptyList(),
) {
  fun toCompletionContext(tokenBudget: Int? = null): CompletionContext {
    val contextFragments = fragments.map { fragment ->
      ContextFragment(
        label = fragment.label,
        content = fragment.content,
        priority = 100,
        maxTokens = 160,
        source = when (fragment.source) {
          "recent-current" -> ContextFragmentSource.RECENT_EDIT
          "open-current" -> ContextFragmentSource.OPEN_TAB
          else -> ContextFragmentSource.TEST
        },
        sourceDetail = fragment.source,
      )
    }
    if (tokenBudget == null) {
      return CompletionContext(languageId, fileName, prefix, suffix, contextFragments)
    }
    val budget = tokenBudget.coerceAtLeast(0)
    val prefixBudget = (budget * 38) / 100
    val suffixBudget = (budget * 18) / 100
    return CompletionContext(
      languageId = languageId,
      fileName = fileName,
      prefix = TextBudget.tail(prefix, prefixBudget),
      suffix = TextBudget.head(suffix, suffixBudget),
      fragments = ContextBudgeter.select(contextFragments, budget - prefixBudget - suffixBudget),
    )
  }

  fun visibleText(): String = buildString {
    append(fileName).append('\n')
    append(languageId).append('\n')
    append(prefix).append('\n')
    append(suffix).append('\n')
    fragments.forEach { fragment ->
      append(fragment.label).append('\n')
      append(fragment.content).append('\n')
    }
  }
}

data class EvalOracle(
  val reference: String = "",
  val expectedGroups: List<List<String>> = emptyList(),
  val negativeExpectation: NegativeExpectation? = null,
  val maxCharacters: Int = 160,
)

data class EvalCase(
  val id: String,
  val surface: String,
  val kind: EvalTaskKind,
  val tags: Set<String>,
  val input: EvalInput,
  val oracle: EvalOracle,
) {
  val providerEligible: Boolean
    get() = kind != EvalTaskKind.NEGATIVE
}

data class EvalDataset(
  val version: String,
  val cases: List<EvalCase>,
) {
  fun casesFor(surface: String): List<EvalCase> = cases.filter { it.surface == surface }

  fun taskCounts(): Map<EvalTaskKind, Int> = EvalTaskKind.entries.associateWith { task ->
    cases.count { it.kind == task }
  }
}
