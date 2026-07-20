package com.kkoemets.subscriptionautocomplete.ide

import com.intellij.driver.client.service
import com.intellij.driver.sdk.FileEditorManager
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
import com.intellij.driver.sdk.ui.components.common.ideFrame
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.absolutePathString
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InstalledPluginSmokeTest {
  @Test
  fun `packaged plugin loads in a real IDE`() {
    val pluginPath = Path.of(requireNotNull(System.getProperty("path.to.build.plugin")))
    val projectPath = Path.of(requireNotNull(javaClass.getResource("/autocomplete-project")).toURI())
    val fakeClaude = fakeClaudeExecutable()
    Starter.newContext(
      testName = "subscriptionAutocompleteInstalledSmoke-${System.nanoTime()}",
      testCase = TestCase(IdeProductProvider.IU, projectInfo = LocalProjectInfo(projectPath)),
    ).apply {
      PluginConfigurator(this).installPluginFromPath(pluginPath)
      writeTestSettings(paths.configDir, fakeClaude)
    }.runIdeWithDriver().useDriverAndCloseIde {
      waitForIndicators(2.minutes)
      ideFrame {
        ideStatusBar {
          val widgetTexts = widgetStatusBarPanel.widgets.list().map { it.text }
          assertTrue(
            widgetTexts.any { it.startsWith("AI ○ idle · Claude") },
            "Expected the installed plugin status widget, found: $widgetTexts",
          )
        }
      }
      exerciseInstalledTyping()
    }
  }

  private fun writeTestSettings(configDirectory: Path, executable: Path) {
    val optionsDirectory = configDirectory.resolve("options")
    Files.createDirectories(optionsDirectory)
    Files.writeString(
      optionsDirectory.resolve("subscriptionAutocomplete.xml"),
      """
        <application>
          <component name="SubscriptionAutocompleteSettings">
            <option name="settingsVersion" value="2" />
            <option name="enabled" value="true" />
            <option name="manualOnly" value="false" />
            <option name="automaticEngine" value="SELECTED_SUBSCRIPTION" />
            <option name="provider" value="CLAUDE" />
            <option name="claudeModel" value="haiku" />
            <option name="claudeExecutable" value="${executable.absolutePathString()}" />
            <option name="debounceMs" value="750" />
            <option name="timeoutSeconds" value="15" />
            <option name="maxOutputTokens" value="512" />
            <option name="syntaxValidationMode" value="SHADOW" />
          </component>
        </application>
      """.trimIndent(),
    )
  }

  private fun com.intellij.driver.client.Driver.exerciseInstalledTyping() {
    val project = singleProject()
    val fileEditorManager = service<FileEditorManager>(project)
    val requirePhysicalTyping = System.getProperty("ideTest.requirePhysicalTyping", "false").toBoolean()
    val repetitions = System.getProperty("ideTest.repetitions", "3").toInt().coerceIn(1, 3)
    repeat(repetitions) {
      typingCases.forEach { case ->
        openFile(case.fileName, project)
        com.intellij.driver.sdk.waitFor(
          "selected editor for ${case.fileName}",
          5.seconds,
        ) { fileEditorManager.getCurrentFile().getName() == case.fileName }
        ideFrame {
          val editor = codeEditorForFile(case.fileName)
          val beforeTyping = case.prefix + case.suffix
          val expected = case.prefix + case.typed + case.completion + case.suffix
          editor.text = beforeTyping
          editor.click()
          editor.moveCaretToOffset(case.prefix.length)
          editor.setFocus()
          val physicalTypingAvailable = editor.robot.hasInputFocus()
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
            10.seconds,
          ) { editor.getInlineCompletion() == case.completion }
          assertTrue(
            !documentsEqual(editor.text, expected),
            "Suggestion was inserted into ${case.fileName} before acceptance",
          )
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
        }
      }
    }
  }

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
}
