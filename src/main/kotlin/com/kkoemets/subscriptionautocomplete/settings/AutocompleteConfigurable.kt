package com.kkoemets.subscriptionautocomplete.settings

import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.diagnostics.DiagnosticsDialog
import com.kkoemets.subscriptionautocomplete.status.ProviderStatusWidget
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel

class AutocompleteConfigurable : Configurable {
  private val enabled = JBCheckBox("Enable inline completions").apply {
    toolTipText = "<html>Allow manual and optional automatic suggestions.<br>" +
      "Each request sends bounded code context directly through your selected provider's installed CLI.</html>"
  }
  private val automaticEngine = ComboBox<AutomaticCompletionEngine>(
    CollectionComboBoxModel(
      listOf(AutomaticCompletionEngine.OFF, AutomaticCompletionEngine.SELECTED_SUBSCRIPTION),
    ),
  )
  private val provider = ComboBox<ProviderKind>(CollectionComboBoxModel(ProviderKind.entries.toList()))
  private val claudeModel = editableCombo(ProviderPolicy.claudeModels).apply {
    toolTipText = "<html>Default: Haiku. It produced the best measured subscription-autocomplete latency<br>" +
      "in the live multi-language evaluation. Choose a larger Claude model only when quality matters more than speed.</html>"
  }
  private val codexModel = editableCombo(ProviderPolicy.codexFallbackChoices).apply {
    toolTipText = "<html>Default: GPT-5.3 Codex Spark. It matched Luna's measured pass rate and completed<br>" +
      "the full LRU-class test in about 4 seconds instead of about 16 seconds.</html>"
  }
  private val codexEffort = editableCombo(ProviderPolicy.codexReasoningEfforts).apply {
    toolTipText = "<html>Default: low, because the default Codex Spark model rejects none.<br>" +
      "Use none only with GPT-5.4, GPT-5.5, or GPT-5.6 models that support reasoning-free requests.</html>"
  }
  private val claudeExecutable = JBTextField()
  private val codexExecutable = JBTextField()
  private val debounceMs = numericSettingField(
    "<html>How long automatic completion waits after you stop typing.<br>" +
      "Lower values start sooner but can send and cancel more requests while you type.<br>" +
      "Increase it if suggestions are repeatedly cancelled; decrease it if they feel slow to start.</html>",
  )
  private val timeoutSeconds = numericSettingField(
    "<html>How long the plugin waits for Claude or Codex before giving up.<br>" +
      "Lower values abandon slow requests sooner. Higher values help large completions finish,<br>" +
      "but a late suggestion may no longer be useful.</html>",
  )
  private val contextTokenBudget = numericSettingField(
    "<html>Default: 1400 tokens. In live tests, 600 and 800 could omit a needed declaration;<br>" +
      "1200 and 1400 retained it and produced correct completions. Automatic requests still use<br>" +
      "smaller caps when possible: 800 for ordinary and 1200 for multiline completion.<br>" +
      "Decrease this only when less shared code or slightly lower latency matters more than distant context.</html>",
  )
  private val maxOutputTokens = numericSettingField(
    "<html>Approximate completion-size target; default: 512 tokens. Providers use this as an instruction,<br>" +
      "while the plugin keeps a larger internal safety envelope because formatted code varies in character length.<br>" +
      "Ordinary automatic suggestions target at most 64 and normal multiline implementations at most 192.<br>" +
      "Lower this for shorter responses; raise it when complete functions or classes stop too early.</html>",
  )
  private val recentEditContext = JBCheckBox("Include bounded recent-edit context").apply {
    toolTipText = "<html>Use small, temporary snippets from files you edited recently so suggestions can follow " +
      "your latest changes.<br>The plugin does not read Git history.</html>"
  }
  private val openTabContext = JBCheckBox("Include bounded open-tab context").apply {
    toolTipText = "<html>Use a small, temporary snippet from another file already open in the IDE.<br>" +
      "It is included only when it looks relevant to what you are typing.</html>"
  }
  private val subscriptionCrossFile = JBCheckBox(
    "Allow cross-file context in Claude/Codex requests, including explicit related-edit proposals",
  ).apply {
    toolTipText = "<html>Allow relevant snippets from other files to be sent to your selected Claude or Codex " +
      "subscription.<br>Leave this off to keep requests limited to the current file.</html>"
  }
  private val diagnostics = JButton("Connection Tests and Diagnostics…")
  private var panel: JPanel? = null

  init {
    diagnostics.addActionListener {
      apply()
      DiagnosticsDialog.open(ProjectManager.getInstance().openProjects.firstOrNull(), modal = true)
    }
  }


  override fun getDisplayName(): String = "Claude/Codex Sub Autocomplete"

  override fun createComponent(): JComponent {
    val form = FormBuilder.createFormBuilder()
      .addComponent(enabled)
      .addLabeledComponent("Automatic typing completions:", automaticEngine)
      .addSeparator()
      .addLabeledComponent("Active provider:", provider)
      .addLabeledComponent("Claude model:", claudeModel)
      .addLabeledComponent("Codex model:", codexModel)
      .addLabeledComponent("Codex reasoning effort:", codexEffort)
      .addSeparator()
      .addLabeledComponent("Claude executable (optional):", claudeExecutable)
      .addLabeledComponent("Codex executable (optional):", codexExecutable)
      .addSeparator()
      .addLabeledComponent("Typing debounce (ms):", debounceMs)
      .addLabeledComponent("Request timeout (seconds):", timeoutSeconds)
      .addLabeledComponent("Input context budget (tokens):", contextTokenBudget)
      .addLabeledComponent("Approximate completion size (tokens):", maxOutputTokens)
      .addSeparator()
      .addComponent(recentEditContext)
      .addComponent(openTabContext)
      .addComponent(subscriptionCrossFile)
      .addComponent(JBLabel(
        "Related-edit proposals are requested explicitly and open as read-only previews; this plugin does not apply them.",
      ))
      .addComponent(diagnostics)
      .addComponent(JBLabel(
        "Only existing Claude Code and ChatGPT subscriptions are accepted. API keys and billed provider fallbacks are ignored.",
      ))
      .addComponent(JBLabel(
        "Requests send bounded code context directly to the selected provider; the plugin operator receives no source code.",
      ))
      .addComponent(JBLabel("The 'AI ○ idle · Claude/Codex' status-bar entry also opens settings, tests, and diagnostics."))
      .addComponentFillVertically(JPanel(), 0)
      .panel
    return JPanel(BorderLayout()).also {
      it.add(form, BorderLayout.NORTH)
      panel = it
      reset()
    }
  }

  override fun isModified(): Boolean {
    val state = AutocompleteSettings.getInstance().state
    return enabled.isSelected != state.enabled ||
      (automaticEngine.selectedItem as? AutomaticCompletionEngine)?.name != state.automaticEngine ||
      (provider.selectedItem as? ProviderKind)?.name != state.provider ||
      editorText(claudeModel) != state.claudeModel ||
      editorText(codexModel) != state.codexModel ||
      editorText(codexEffort) != state.codexReasoningEffort ||
      claudeExecutable.text != state.claudeExecutable ||
      codexExecutable.text != state.codexExecutable ||
      debounceMs.text != state.debounceMs.toString() ||
      timeoutSeconds.text != state.timeoutSeconds.toString() ||
      contextTokenBudget.text != state.contextTokenBudget.toString() ||
      maxOutputTokens.text != state.maxOutputTokens.toString() ||
      recentEditContext.isSelected != state.recentEditContextEnabled ||
      openTabContext.isSelected != state.openTabContextEnabled ||
      subscriptionCrossFile.isSelected != state.allowCrossFileForSubscription
  }

  override fun apply() {
    AutocompleteSettings.getInstance().update { state ->
      state.enabled = enabled.isSelected
      state.automaticEngine = (
        automaticEngine.selectedItem as? AutomaticCompletionEngine ?: AutomaticCompletionEngine.OFF
      ).name
      state.provider = (provider.selectedItem as? ProviderKind ?: ProviderKind.CODEX).name
      state.claudeModel = editorText(claudeModel).ifBlank { ProviderPolicy.DEFAULT_CLAUDE_MODEL }
      state.codexModel = editorText(codexModel).ifBlank { ProviderPolicy.DEFAULT_CODEX_MODEL }
      state.codexReasoningEffort = editorText(codexEffort).ifBlank { ProviderPolicy.DEFAULT_CODEX_EFFORT }
      state.claudeExecutable = claudeExecutable.text.trim()
      state.codexExecutable = codexExecutable.text.trim()
      state.debounceMs = debounceMs.text.toIntOrNull()?.coerceIn(100, 3000) ?: 750
      state.timeoutSeconds = timeoutSeconds.text.toIntOrNull()?.coerceIn(2, 120) ?: 15
      state.contextTokenBudget = contextTokenBudget.text.toIntOrNull()?.coerceIn(600, 4000) ?: 1400
      state.maxOutputTokens = maxOutputTokens.text.toIntOrNull()?.coerceIn(16, 512) ?: 512
      state.recentEditContextEnabled = recentEditContext.isSelected
      state.openTabContextEnabled = openTabContext.isSelected
      state.allowCrossFileForSubscription = subscriptionCrossFile.isSelected
    }
    ProjectManager.getInstance().openProjects.forEach { project ->
      WindowManager.getInstance().getStatusBar(project)?.updateWidget(ProviderStatusWidget.ID)
    }
  }

  override fun reset() {
    val service = AutocompleteSettings.getInstance()
    val state = service.state
    enabled.isSelected = state.enabled
    automaticEngine.selectedItem = service.selectedAutomaticEngine().takeUnless {
      it == AutomaticCompletionEngine.BUNDLED_FIM
    } ?: AutomaticCompletionEngine.OFF
    provider.selectedItem = service.selectedProvider()
    setEditorText(claudeModel, state.claudeModel)
    setEditorText(codexModel, state.codexModel)
    setEditorText(codexEffort, state.codexReasoningEffort)
    claudeExecutable.text = state.claudeExecutable
    codexExecutable.text = state.codexExecutable
    debounceMs.text = state.debounceMs.toString()
    timeoutSeconds.text = state.timeoutSeconds.toString()
    contextTokenBudget.text = state.contextTokenBudget.toString()
    maxOutputTokens.text = state.maxOutputTokens.toString()
    recentEditContext.isSelected = state.recentEditContextEnabled
    openTabContext.isSelected = state.openTabContextEnabled
    subscriptionCrossFile.isSelected = state.allowCrossFileForSubscription
  }

  override fun disposeUIResources() {
    panel = null
  }

  private fun editableCombo(values: List<String>): ComboBox<String> =
    ComboBox(CollectionComboBoxModel(values)).apply { isEditable = true }

  private fun numericSettingField(tooltip: String): JBTextField =
    JBTextField().apply { toolTipText = tooltip }

  private fun editorText(comboBox: ComboBox<String>): String = comboBox.editor.item?.toString()?.trim().orEmpty()

  private fun setEditorText(comboBox: ComboBox<String>, value: String) {
    comboBox.editor.item = value
    comboBox.selectedItem = value
  }
}
