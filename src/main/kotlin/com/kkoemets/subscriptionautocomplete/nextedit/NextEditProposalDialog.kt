package com.kkoemets.subscriptionautocomplete.nextedit

import com.kkoemets.subscriptionautocomplete.context.ContextFilePolicy
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel

internal class NextEditProposalDialog(
  private val project: Project,
  private val proposal: NextEditProposal,
  private val providerName: String,
  private val modelName: String,
) : DialogWrapper(project, false) {
  private val edits = JBList(proposal.edits).apply {
    selectionMode = ListSelectionModel.SINGLE_SELECTION
    cellRenderer = EditRenderer()
  }
  private val target = JBLabel()
  private val stale = JBLabel()
  private val rationale = JBTextArea().apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    background = null
  }
  private val before = codeArea()
  private val after = codeArea()
  private val openTarget = JButton("Open Target")
  private val copyProposed = JButton("Copy Proposed Text")
  private val staleRefreshTimer = javax.swing.Timer(STALE_REFRESH_MILLIS) { refreshStaleness() }

  init {
    title = "Related Edit Proposal"
    setResizable(true)
    setCancelButtonText("Close")
    edits.addListSelectionListener { if (!it.valueIsAdjusting) refreshSelection() }
    openTarget.addActionListener { openSelectedTarget() }
    copyProposed.addActionListener { copySelectedProposal() }
    init()
    if (proposal.edits.isNotEmpty()) edits.selectedIndex = 0
    staleRefreshTimer.start()
  }

  override fun createCenterPanel(): JComponent {
    val currentPanel = JPanel(BorderLayout(0, 4)).apply {
      add(JBLabel("Current text"), BorderLayout.NORTH)
      add(JBScrollPane(before), BorderLayout.CENTER)
    }
    val proposedPanel = JPanel(BorderLayout(0, 4)).apply {
      add(JBLabel("Proposed replacement"), BorderLayout.NORTH)
      add(JBScrollPane(after), BorderLayout.CENTER)
    }
    val comparison = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, currentPanel, proposedPanel).apply {
      resizeWeight = 0.5
      isContinuousLayout = true
    }
    val details = JPanel(BorderLayout(0, 6)).apply {
      add(JPanel(GridLayout(0, 1, 0, 2)).apply {
        add(target)
        add(stale)
        add(rationale)
      }, BorderLayout.NORTH)
      add(comparison, BorderLayout.CENTER)
      add(JPanel().apply {
        add(openTarget)
        add(copyProposed)
      }, BorderLayout.SOUTH)
    }
    return JPanel(BorderLayout(8, 8)).apply {
      border = JBUI.Borders.empty(8)
      add(JPanel(GridLayout(0, 1, 0, 2)).apply {
        add(JBLabel("Preview only — this plugin will not change any file."))
        add(JBLabel("Provider: $providerName · Model: $modelName"))
        add(JBLabel("Validation: uniquely anchored only; syntax and semantics are not validated."))
        add(JBLabel("Proposal: ${proposal.summary}"))
      }, BorderLayout.NORTH)
      add(JBScrollPane(edits).apply { preferredSize = Dimension(210, 420) }, BorderLayout.WEST)
      add(details, BorderLayout.CENTER)
      preferredSize = Dimension(980, 600)
    }
  }

  override fun createActions(): Array<Action> = arrayOf(cancelAction)

  override fun dispose() {
    staleRefreshTimer.stop()
    super.dispose()
  }

  private fun refreshSelection() {
    val item = edits.selectedValue
    target.text = item?.let { "Target: ${it.targetName}" }.orEmpty()
    rationale.text = item?.rationale.orEmpty()
    before.text = item?.before.orEmpty()
    after.text = item?.after.orEmpty()
    before.caretPosition = 0
    after.caretPosition = 0
    refreshStaleness()
  }

  private fun refreshStaleness() {
    val item = edits.selectedValue
    val staleTarget = item == null || isStale(item)
    stale.text = if (staleTarget) {
      "Warning: the target changed after this proposal was requested; actions are disabled."
    } else {
      "Target is unchanged since context capture."
    }
    openTarget.isEnabled = !staleTarget && item.targetFile?.isValid == true
    copyProposed.isEnabled = !staleTarget
  }

  private fun isStale(item: NextEditProposalItem): Boolean {
    val file = item.targetFile ?: return true
    val identity = item.targetFileIdentity ?: return true
    return ApplicationManager.getApplication().runReadAction<Boolean> {
      if (!file.isValid || captureFileIdentity(file) != identity || !ContextFilePolicy(project).isEligible(file)) {
        return@runReadAction true
      }
      val document = FileDocumentManager.getInstance().getCachedDocument(file) ?: return@runReadAction true
      document.modificationStamp != item.targetModificationStamp || occurrences(document.text, item.before) != 1
    }
  }

  private fun openSelectedTarget() {
    val item = edits.selectedValue ?: return
    if (isStale(item)) {
      refreshStaleness()
      return
    }
    val file = item.targetFile?.takeIf { it.isValid } ?: return
    val offset = FileDocumentManager.getInstance().getCachedDocument(file)
      ?.text
      ?.indexOf(item.before)
      ?: return
    if (offset < 0) {
      refreshStaleness()
      return
    }
    OpenFileDescriptor(project, file, offset).navigate(true)
  }

  private fun copySelectedProposal() {
    val item = edits.selectedValue ?: return
    if (isStale(item)) {
      refreshStaleness()
      return
    }
    CopyPasteManager.getInstance().setContents(StringSelection(item.after))
  }

  private fun occurrences(text: String, value: String): Int {
    var count = 0
    var offset = text.indexOf(value)
    while (offset >= 0) {
      count += 1
      if (count > 1) return count
      offset = text.indexOf(value, offset + value.length)
    }
    return count
  }

  private fun codeArea() = JBTextArea().apply {
    isEditable = false
    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
  }

  private class EditRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
      list: JList<*>?,
      value: Any?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean,
    ): Component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
      text = (value as? NextEditProposalItem)?.let { item -> "${index + 1}. ${item.targetName}" }.orEmpty()
    }
  }

  private companion object {
    const val STALE_REFRESH_MILLIS = 500
  }
}
