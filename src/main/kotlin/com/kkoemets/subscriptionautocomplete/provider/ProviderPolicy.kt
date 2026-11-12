package com.kkoemets.subscriptionautocomplete.provider

/**
 * The models and reasoning efforts the plugin offers.
 *
 * Every entry was accepted by a live subscription request when it was added. Keep the lists limited
 * to models the subscriptions still serve: a retired model fails every completion.
 */
object ProviderPolicy {
  const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"
  const val DEFAULT_CODEX_MODEL = "gpt-5.6-luna"
  const val DEFAULT_CODEX_EFFORT = "low"

  val claudeModels = listOf(DEFAULT_CLAUDE_MODEL, "claude-sonnet-5-5", "claude-opus-5-5")
  val codexModels = listOf(DEFAULT_CODEX_MODEL, "gpt-5.6-terra")
  val codexReasoningEfforts = listOf("none", "low", "medium", "high", "xhigh", "max")

  /** Previously offered Codex models that ChatGPT subscriptions now reject with `model_not_found`. */
  val retiredCodexModels = setOf("gpt-5.3-codex-spark", "gpt-5.6-sol", "gpt-5.5", "gpt-5.4")

  /**
   * Model names are typed by users and passed to the CLI as a process argument. Restricting them to
   * the characters real model identifiers use keeps the argument identical on every launcher,
   * including Windows `cmd.exe` shims where quotes, spaces and `%` are rewritten.
   */
  fun isValidModelName(model: String): Boolean = MODEL_NAME.matches(model)

  /** Efforts are free text in the settings; anything shaped like an effort word is sent to Codex as typed. */
  fun isValidReasoningEffort(effort: String): Boolean = EFFORT_NAME.matches(effort)

  fun invalidModelMessage(provider: String, model: String): String =
    "$provider model name \"${model.take(60)}\" is not valid. " +
      "Use letters, digits and . _ : / @ [ ] - only; choose a model in the plugin settings."

  private val EFFORT_NAME = Regex("[a-z]{1,16}")
  private val MODEL_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._:/@\\[\\]-]{0,119}")
}
