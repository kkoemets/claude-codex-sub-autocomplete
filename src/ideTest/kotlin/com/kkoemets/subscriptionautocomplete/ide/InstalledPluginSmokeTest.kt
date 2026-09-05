package com.kkoemets.subscriptionautocomplete.ide

import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.driver.sdk.ui.components.common.dialogs.ideStatusBar
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.kkoemets.subscriptionautocomplete.provider.ExecutableResolver
import com.kkoemets.subscriptionautocomplete.provider.ProviderPolicy
import com.kkoemets.subscriptionautocomplete.provider.SubscriptionAuth
import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolutePathString
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class InstalledPluginSmokeTest {
  @TestFactory
  fun `packaged plugin loads in a real IDE`(): List<DynamicTest> {
    val live = System.getProperty("ideTest.live", "false").toBoolean()
    val providers = if (live) {
      System.getProperty("ideTest.liveProviders", "").split(',')
        .map(String::trim).filter(String::isNotEmpty)
        .map { ProviderKind.valueOf(it.uppercase()) }.distinct().also {
          require(it.isNotEmpty()) { "Live installed-IDE checks require explicit providers" }
        }
    } else listOf(null)
    return providers.map { provider ->
      DynamicTest.dynamicTest("${provider?.name ?: "fixture"} ghost text requires acceptance") {
        runInstalledSmoke(provider)
      }
    }
  }

  private fun runInstalledSmoke(liveProvider: ProviderKind?) {
    val pluginPath = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
    val projectPath = Path.of(requireNotNull(javaClass.getResource("/autocomplete-project")).toURI())
    val ideVersion = requireNotNull(System.getProperty("ideTest.ideVersion"))
    val provider = liveProvider ?: ProviderKind.CLAUDE
    if (liveProvider != null) {
      val profile = if (provider == ProviderKind.CLAUDE) ProviderPolicy.DEFAULT_CLAUDE_MODEL else {
        System.getProperty("ideTest.codexModel", ProviderPolicy.DEFAULT_CODEX_MODEL) + "/" +
          System.getProperty("ideTest.codexEffort", ProviderPolicy.DEFAULT_CODEX_EFFORT)
      }
      println("Installed live profile: ${provider.name}/$profile")
    }
    val executable = if (liveProvider == null) fakeClaudeExecutable() else {
      val command = provider.name.lowercase()
      requireNotNull(ExecutableResolver.resolve(command, "")) { "$command executable was not found" }
        .also { path ->
          val error = when (provider) {
            ProviderKind.CLAUDE -> SubscriptionAuth.verifyClaude(path, projectPath, forceRefresh = true)
            ProviderKind.CODEX -> SubscriptionAuth.verifyCodex(path, projectPath, forceRefresh = true)
          }
          check(error == null) { requireNotNull(error) }
        }
    }
    Starter.newContext(
      testName = "subscriptionAutocomplete-${liveProvider?.name ?: "fixture"}-${System.nanoTime()}",
      testCase = TestCase(IdeProductProvider.IU, projectInfo = LocalProjectInfo(projectPath))
        .useRelease(ideVersion),
    ).apply {
      PluginConfigurator(this).installPluginFromPath(pluginPath)
      writeTestSettings(paths.configDir, executable, ideVersion, provider, liveProvider != null)
    }.runIdeWithDriver().useDriverAndCloseIde {
      waitForIndicators(2.minutes)
      ideFrame {
        ideStatusBar {
          val widgetTexts = widgetStatusBarPanel.widgets.list().map { it.text }
          val expectedActivity = if (liveProvider != null) "AI ⌨ hotkey" else "AI ○ idle"
          val expectedProvider = if (provider == ProviderKind.CLAUDE) "Claude" else "Codex"
          assertTrue(
            widgetTexts.any { it.startsWith("$expectedActivity · $expectedProvider") },
            "Expected the installed plugin status widget, found: $widgetTexts",
          )
        }
      }
      exerciseInstalledTyping(liveProvider != null)
    }
  }

  private fun writeTestSettings(
    configDirectory: Path,
    executable: Path,
    ideVersion: String,
    provider: ProviderKind,
    live: Boolean,
  ) {
    val optionsDirectory = configDirectory.resolve("options")
    Files.createDirectories(optionsDirectory)
    Files.writeString(
      optionsDirectory.resolve("subscriptionAutocomplete.xml"),
      """
        <application>
          <component name="SubscriptionAutocompleteSettings">
            <option name="settingsVersion" value="6" />
            <option name="enabled" value="true" />
            <option name="manualOnly" value="$live" />
            <option name="automaticEngine" value="${if (live) "OFF" else "SELECTED_SUBSCRIPTION"}" />
            <option name="provider" value="${provider.name}" />
            <option name="claudeModel" value="${ProviderPolicy.DEFAULT_CLAUDE_MODEL}" />
            <option name="codexModel" value="${xmlAttribute(System.getProperty("ideTest.codexModel", ProviderPolicy.DEFAULT_CODEX_MODEL))}" />
            <option name="codexReasoningEffort" value="${xmlAttribute(System.getProperty("ideTest.codexEffort", ProviderPolicy.DEFAULT_CODEX_EFFORT))}" />
            <option name="${provider.name.lowercase()}Executable" value="${xmlAttribute(executable.absolutePathString())}" />
            <option name="debounceMs" value="750" />
            <option name="timeoutSeconds" value="${if (live) 30 else 15}" />
            <option name="maxOutputTokens" value="512" />
            <option name="syntaxValidationMode" value="SHADOW" />
          </component>
        </application>
      """.trimIndent(),
    )
    val releaseLine = ideVersion.split('.').take(2).joinToString(".")
    val releaseParts = releaseLine.split('.').map(String::toInt)
    val platformBaseline = (releaseParts[0] - 2000) * 10 + releaseParts[1]
    // A modal trial-feedback survey blocks inline rendering in an otherwise ready editor.
    // Record it as already dismissed only in this test's temporary IDE configuration.
    val feedbackDismissedAt = java.lang.Long.toHexString(System.currentTimeMillis())
    Files.writeString(
      optionsDirectory.resolve("other.xml"),
      """
        <application>
          <component name="PropertyService"><![CDATA[{
            "keyToString": {
              "evlsprt.$platformBaseline": "$feedbackDismissedAt"
            },
            "keyToStringList": {
              "trial.active.editor.tab.shown.versions": ["IU-$releaseLine"]
            }
          }]]></component>
        </application>
      """.trimIndent(),
    )
  }

  private fun com.intellij.driver.client.Driver.exerciseInstalledTyping(live: Boolean) {
    val project = singleProject()
    val requirePhysicalTyping = System.getProperty("ideTest.requirePhysicalTyping", "false").toBoolean()
    val repetitions = if (live) 1 else System.getProperty("ideTest.repetitions", "3").toInt().coerceIn(1, 3)
    repeat(repetitions) {
      (if (live) liveTypingCases else typingCases).forEach { case ->
        openFile(case.fileName, project)
        ideFrame {
          editorTabs { clickTab(case.fileName) }
          val editor = codeEditorForFile(case.fileName)
          val beforeTyping = case.prefix + case.suffix
          editor.text = beforeTyping
          editor.click()
          editor.moveCaretToOffset(case.prefix.length)
          editor.setFocus()
          val physicalTypingAvailable = requirePhysicalTyping && editor.isFocusOwner()
          check(physicalTypingAvailable || !requirePhysicalTyping) {
            "Physical typing requires macOS Accessibility/input focus for the launched IntelliJ process"
          }
          if (physicalTypingAvailable) {
            val beforePhysicalTyping = editor.text
            case.typed.forEach(editor.robot::type)
            com.intellij.driver.sdk.waitFor(
              "physical typing in ${case.fileName}",
              5.seconds,
            ) { editor.text != beforePhysicalTyping }
          } else {
            editor.text = case.prefix + case.typed + case.suffix
            editor.moveCaretToOffset(case.prefix.length + case.typed.length)
          }
          assertTrue(editor.text.startsWith(case.prefix + case.typed))
          invokeAction(
            actionId = "SubscriptionAutocomplete.TriggerCompletion",
            now = true,
            component = editor.component,
            place = "SubscriptionAutocompleteIdeTest",
          )
          com.intellij.driver.sdk.waitFor(
            "inline completion for ${case.fileName}",
            (if (live) 35 else 10).seconds,
          ) {
            inlineCompletionText(
              editor,
              case.prefix.length + case.typed.length,
            ).isNotEmpty()
          }
          val suggestion = inlineCompletionText(editor, case.prefix.length + case.typed.length)
          assertTrue(
            documentsEqual(editor.text, case.prefix + case.typed + case.suffix),
            "Suggestion was inserted into ${case.fileName} before acceptance",
          )
          if (live) {
            assertTrue(
              Regex("value\\s*\\*\\s*2|2\\s*\\*\\s*value|value\\s*\\+\\s*value")
                .containsMatchIn(suggestion),
              "Live completion did not implement doubling in ${case.fileName}",
            )
          }
          val expected = case.prefix + case.typed + (if (live) suggestion else case.completion) + case.suffix
          if (physicalTypingAvailable) {
            editor.keyboard { tab() }
          } else {
            invokeAction(
              actionId = "InsertInlineCompletionAction",
              now = true,
              component = editor.component,
              place = "SubscriptionAutocompleteIdeTest",
            )
          }
          com.intellij.driver.sdk.waitFor(
            "accepted completion for ${case.fileName}",
            5.seconds,
          ) { documentsEqual(editor.text, expected) }
          println(
            "${if (live) "Live" else "Fixture"} ${case.fileName}: ghost text displayed; " +
              "explicit acceptance preserved surrounding text; physical typing: $physicalTypingAvailable",
          )
        }
      }
    }
  }

  private fun xmlAttribute(value: String): String = value.replace("&", "&amp;")
    .replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

  private fun fakeClaudeExecutable(): Path {
    val executable = Files.createTempFile("subscription-autocomplete-fake-claude-", ".sh")
    javaClass.getResourceAsStream("/fake-claude.sh").use { input ->
      requireNotNull(input) { "Missing fake Claude test executable" }
      Files.copy(input, executable, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    Files.setPosixFilePermissions(
      executable,
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
      ),
    )
    executable.toFile().deleteOnExit()
    return executable
  }

  private fun documentsEqual(actual: String, expected: String): Boolean =
    actual.trimEnd('\r', '\n') == expected.trimEnd('\r', '\n')

  private fun inlineCompletionText(editor: JEditorUiComponent, caretOffset: Int): String {
    val method = editor.javaClass.methods.single {
      it.name == "getInlineCompletion" && it.parameterCount == 1
    }
    val value = if (method.parameterTypes.single().isPrimitive) {
      method.invoke(editor, caretOffset)
    } else {
      method.invoke(editor, null)
    }
    return when (value) {
      is String -> value
      is List<*> -> value.joinToString("") { hint ->
        hint?.javaClass?.methods
          ?.singleOrNull { it.name == "getText" && it.parameterCount == 0 }
          ?.invoke(hint) as? String ?: ""
      }
      else -> error("Unexpected inline completion result: ${value?.javaClass?.name}")
    }
  }

  private data class TypingCase(
    val fileName: String,
    val prefix: String,
    val typed: String,
    val completion: String,
    val suffix: String,
  )

  private val typingCases = listOf(
    TypingCase(
      "media-demo.ts",
      "type User = { name: string; active: boolean }\n\n" +
        "// Return active user names sorted alphabetically.\n" +
        "function activeUserNames(users: User[]): string[] {\n  ",
      "ret",
      "urn users\n" +
        "    .filter((user) => user.active)\n" +
        "    .map((user) => user.name)\n" +
        "    .sort((left, right) => left.localeCompare(right))\n" +
        "}",
      "\n",
    ),
    TypingCase("sample.ts", "// this wil", "l", " install dependencies", "\n"),
    TypingCase("sample.js", "export const doubl", "e", " = (value) => value * 2", "\n"),
    TypingCase("sample.py", "doubl", "e", " = lambda value: value * 2", "\n"),
    TypingCase("sample.sh", "app_en", "v", "=production", "\n"),
    TypingCase("Sample.java", "// this wil", "l", " install dependencies", "\n"),
    TypingCase("Sample.kt", "// this wil", "l", " install dependencies", "\n"),
    TypingCase("docker-compose.yml", "# this wil", "l", " install dependencies", "\n"),
    TypingCase("sample.sql", "-- this wil", "l", " install dependencies", "\n"),
    TypingCase("sample.html", "<!-- this wil", "l", " install dependencies -->", "\n"),
    TypingCase("sample.json", "{\n  \"private\": fals", "e", ",", "\n}\n"),
    TypingCase("Dockerfile", "# this wil", "l", " install dependencies", "\n"),
  )

  private val liveTypingCases = listOf(
    TypingCase(
      "sample.ts",
      "// Return twice the input value.\nexport function double(value: number): number {\n  ",
      "return ", "value * 2", "\n}\n",
    ),
    TypingCase(
      "sample.py",
      "# Return twice the input value.\ndef double(value):\n    ",
      "return ", "value * 2", "\n",
    ),
    TypingCase(
      "Sample.java",
      "class Sample {\n  // Return twice the input value.\n  static int twice(int value) {\n    ",
      "return ", "value * 2", ";\n  }\n}\n",
    ),
  )
}
