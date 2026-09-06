package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.terminal.frontend.view.TerminalView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.view.shellIntegration.getTypedCommandText

/** Only shell integration can distinguish editable input from a program's output. */
@Suppress("UnstableApiUsage")
internal object TerminalPromptInput {
  @OptIn(ExperimentalCoroutinesApi::class)
  fun read(terminal: TerminalView): String? {
    val deferred = terminal.shellIntegrationDeferred
    if (!deferred.isCompleted || deferred.isCancelled) return null
    val integration = runCatching { deferred.getCompleted() }.getOrNull() ?: return null
    if (integration.outputStatus.value !is TerminalOutputStatus.TypingCommand) return null
    val models = terminal.outputModels
    if (models.active.value !== models.regular) return null
    val block = integration.blocksModel.activeBlock as? TerminalCommandBlock ?: return null
    return runCatching { block.getTypedCommandText(models.regular) }.getOrNull()
  }
}
