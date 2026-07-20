package com.kkoemets.subscriptionautocomplete.diagnostics

import com.kkoemets.subscriptionautocomplete.completion.COMPLETION_ACTIVITY_TOPIC
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivityListener
import com.kkoemets.subscriptionautocomplete.completion.CompletionActivitySnapshot
import com.kkoemets.subscriptionautocomplete.completion.CompletionRuntimeState
import com.kkoemets.subscriptionautocomplete.provider.ConnectivityResult
import com.kkoemets.subscriptionautocomplete.provider.ProviderConnectivityTester
import com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.JViewport

class DiagnosticsDialog(
  private val project: Project?,
  modal: Boolean,
) : DialogWrapper(project, false) {
  private val output = JBTextArea().apply {
    isEditable = false
    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
  }
  private val status = JBLabel("Ready. Login tests are local; model tests use one subscription request.")
  private val completionSummary = JBLabel("Last completion: none.")
  private val loginButton = JButton("Test Subscription Login")
  private val modelButton = JButton("Test Selected Model")
  private val refreshTimer = Timer(250) { refreshOutputNow() }.apply { isRepeats = false }
  @Volatile
  private var dialogDisposed = false

  init {
    title = "Claude/Codex Sub Autocomplete Diagnostics"
    setModal(modal)
    setResizable(true)
    setCancelButtonText("Close")
    ApplicationManager.getApplication().messageBus.connect(myDisposable).subscribe(
      DiagnosticsLog.TOPIC,
      DiagnosticsListener { scheduleRefresh() },
    )
    project?.messageBus?.connect(myDisposable)?.subscribe(
      COMPLETION_ACTIVITY_TOPIC,
      CompletionActivityListener { snapshot -> scheduleCompletionSummaryRefresh(snapshot) },
    )
    Disposer.register(myDisposable) {
      dialogDisposed = true
      refreshTimer.stop()
    }
    loginButton.addActionListener { runConnectivityTest(liveModel = false) }
    modelButton.addActionListener { runConnectivityTest(liveModel = true) }
    init()
    refreshCompletionSummaryNow()
    refreshOutputNow()
  }

  override fun createCenterPanel(): JComponent {
    val testPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
      add(loginButton)
      add(modelButton)
      add(JButton("Refresh Log").apply { addActionListener { refreshOutputNow() } })
      add(JButton("Copy Log").apply {
        addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(output.text)) }
      })
      add(JButton("Clear Log").apply { addActionListener { DiagnosticsLog.getInstance().clear() } })
    }
    return JPanel(BorderLayout(0, 8)).apply {
      border = JBUI.Borders.empty(8)
      add(JPanel(BorderLayout(0, 6)).apply {
        add(JPanel(GridLayout(0, 1, 0, 2)).apply {
          add(status)
          add(completionSummary)
        }, BorderLayout.NORTH)
        add(testPanel, BorderLayout.CENTER)
      }, BorderLayout.NORTH)
      add(JBScrollPane(output), BorderLayout.CENTER)
      preferredSize = Dimension(850, 520)
    }
  }

  override fun createActions(): Array<Action> = arrayOf(cancelAction)

  private fun runConnectivityTest(liveModel: Boolean) {
    val settingsService = AutocompleteSettings.getInstance()
    val provider = settingsService.selectedProvider()
    val settings = settingsService.state.copy()
    setTestRunning(true)
    status.text = if (liveModel) {
      "Testing ${provider.displayName} model ${selectedModel(provider, settings)}…"
    } else {
      "Testing ${provider.displayName} executable and subscription login…"
    }
    ApplicationManager.getApplication().executeOnPooledThread {
      val result = runBlocking {
        if (liveModel) {
          ProviderConnectivityTester.testCompletion(provider, settings)
        } else {
          ProviderConnectivityTester.testLogin(provider, settings)
        }
      }
      ApplicationManager.getApplication().invokeLater({
        if (!isShowing) return@invokeLater
        showResult(result)
        setTestRunning(false)
      }, ModalityState.any())
    }
  }

  private fun showResult(result: ConnectivityResult) {
    status.text = (if (result.successful) "✓ " else "✕ ") + result.summary
    refreshOutputNow()
  }

  private fun setTestRunning(running: Boolean) {
    loginButton.isEnabled = !running
    modelButton.isEnabled = !running
  }

  private fun scheduleRefresh() {
    if (dialogDisposed) return
    if (!SwingUtilities.isEventDispatchThread()) {
      ApplicationManager.getApplication().invokeLater({ scheduleRefresh() }, ModalityState.any())
      return
    }
    refreshTimer.restart()
  }

  private fun scheduleCompletionSummaryRefresh(snapshot: CompletionActivitySnapshot) {
    if (!SwingUtilities.isEventDispatchThread()) {
      ApplicationManager.getApplication().invokeLater(
        { scheduleCompletionSummaryRefresh(snapshot) },
        ModalityState.any(),
      )
      return
    }
    if (!dialogDisposed) updateCompletionSummary(snapshot)
  }

  private fun refreshCompletionSummaryNow() {
    val activeProject = project?.takeUnless { it.isDisposed } ?: return
    updateCompletionSummary(CompletionRuntimeState.getInstance(activeProject).activitySnapshot())
  }

  private fun updateCompletionSummary(snapshot: CompletionActivitySnapshot) {
    completionSummary.text = DiagnosticsActivityPresentation.summary(
      snapshot,
      AutocompleteSettings.getInstance().selectedProvider(),
    )
  }

  private fun refreshOutputNow() {
    if (dialogDisposed) return
    if (!SwingUtilities.isEventDispatchThread()) {
      ApplicationManager.getApplication().invokeLater({ refreshOutputNow() }, ModalityState.any())
      return
    }
    refreshTimer.stop()
    val text = DiagnosticsLog.getInstance().snapshot().joinToString("\n\n") { entry ->
      buildString {
        append(TIME_FORMAT.format(entry.timestamp.atZone(ZoneId.systemDefault())))
        append("  ").append(entry.level.name.padEnd(7)).append("  ").append(entry.summary)
        if (entry.details.isNotBlank()) append("\n").append(entry.details.prependIndent("  "))
      }
    }
    output.text = text.ifBlank {
      "No diagnostic events yet. Trigger an inline completion or run a connectivity test."
    }
    output.caretPosition = output.document.length
    SwingUtilities.invokeLater {
      val viewport = SwingUtilities.getAncestorOfClass(JViewport::class.java, output) as? JViewport
      if (viewport != null) {
        viewport.viewPosition = Point(0, (output.height - viewport.extentSize.height).coerceAtLeast(0))
      }
    }
  }

  private fun selectedModel(
    provider: ProviderKind,
    settings: AutocompleteSettings.SettingsState,
  ): String = when (provider) {
    ProviderKind.CLAUDE -> settings.claudeModel
    ProviderKind.CODEX -> settings.codexModel
  }

  companion object {
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun open(project: Project?, modal: Boolean = false) {
      DiagnosticsDialog(project, modal).show()
    }
  }
}
