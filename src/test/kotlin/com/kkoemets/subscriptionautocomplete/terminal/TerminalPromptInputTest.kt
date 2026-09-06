package com.kkoemets.subscriptionautocomplete.terminal

import com.intellij.terminal.frontend.view.TerminalView
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.plugins.terminal.view.TerminalOffset
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.jetbrains.plugins.terminal.view.TerminalOutputModelsSet
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalBlocksModel
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("UnstableApiUsage")
class TerminalPromptInputTest {
  @Test
  fun `reads only editable command block at an idle prompt`() {
    val fixture = Fixture()
    assertEquals("# list files", TerminalPromptInput.read(fixture.view))
  }

  @Test
  fun `program output resembling a request is never captured`() {
    val fixture = Fixture()
    fixture.status.value = TerminalOutputStatus.ExecutingCommand
    assertNull(TerminalPromptInput.read(fixture.view))
    assertEquals(0, fixture.textReads)
  }

  @Test
  fun `alternate screen rejects even a stale typing status`() {
    val fixture = Fixture()
    fixture.active.value = fake<TerminalOutputModel>()
    assertNull(TerminalPromptInput.read(fixture.view))
    assertEquals(0, fixture.textReads)
  }

  @Test
  fun `missing pending cancelled and failed shell integration fail closed`() {
    val missing = CompletableDeferred<TerminalShellIntegration?>().also { it.complete(null) }
    val pending = CompletableDeferred<TerminalShellIntegration?>()
    val cancelled = CompletableDeferred<TerminalShellIntegration?>().also { it.cancel() }
    val failed = CompletableDeferred<TerminalShellIntegration?>().also { it.completeExceptionally(IllegalStateException("shell ended")) }
    for (integration in listOf(missing, pending, cancelled, failed)) {
      assertNull(TerminalPromptInput.read(fake<TerminalView>("getShellIntegrationDeferred" to { integration })))
    }
  }

  @Test
  fun `recapture rejects a command that starts while generation is pending`() {
    val fixture = Fixture()
    assertEquals("# list files", TerminalPromptInput.read(fixture.view))
    fixture.status.value = TerminalOutputStatus.ExecutingCommand
    assertNull(TerminalPromptInput.read(fixture.view))
    assertEquals(1, fixture.textReads)
  }

  private class Fixture {
    var textReads = 0
    val status = MutableStateFlow<TerminalOutputStatus>(TerminalOutputStatus.TypingCommand)
    private val text = "# list files"
    private val start = TerminalOffset.of(0)
    private val end = TerminalOffset.of(text.length.toLong())
    private val model = fake<TerminalOutputModel>(
      "getStartOffset" to { start },
      "getEndOffset" to { end },
      "getText" to { textReads++; text },
    )
    val active = MutableStateFlow(model)
    private val models = fake<TerminalOutputModelsSet>("getRegular" to { model }, "getActive" to { active })
    private val block = fake<TerminalCommandBlock>(
      "getCommandStartOffset" to { start }, "getOutputStartOffset" to { null }, "getEndOffset" to { end },
    )
    private val blocks = fake<TerminalBlocksModel>("getActiveBlock" to { block })
    private val integration = fake<TerminalShellIntegration>("getOutputStatus" to { status }, "getBlocksModel" to { blocks })
    val view = fake<TerminalView>(
      "getShellIntegrationDeferred" to { CompletableDeferred(integration) },
      "getOutputModels" to { models },
    )
  }

  companion object {
    private inline fun <reified T> fake(vararg methods: Pair<String, () -> Any?>): T {
      val implementations = methods.toMap()
      return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
          "equals" -> proxy === args?.get(0)
          "hashCode" -> System.identityHashCode(proxy)
          "toString" -> T::class.java.simpleName
          else -> implementations[method.name]?.invoke()
            ?: if (implementations.containsKey(method.name)) null else error("Unexpected terminal access: ${method.name}")
        }
      } as T
    }
  }
}
