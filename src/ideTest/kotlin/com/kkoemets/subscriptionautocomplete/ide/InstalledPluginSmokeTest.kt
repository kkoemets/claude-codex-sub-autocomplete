package com.kkoemets.subscriptionautocomplete.ide

import com.intellij.driver.sdk.openFile
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitFor
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.driver.sdk.ui.components.common.dialogs.ideStatusBar
import com.intellij.driver.sdk.ui.components.common.codeEditorForFile
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
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
  private val productCode = System.getProperty("ideTest.productCode", "IU")
  private var preparedInstallationRoot: Path? = null
  private lateinit var installedApplication: Path

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
      val surface = when {
        System.getProperty("ideTest.reworkedOnly", "false").toBoolean() -> "Reworked terminal review"
        System.getProperty("ideTest.terminalsOnly", "false").toBoolean() -> "terminal regression"
        else -> "ghost text requires acceptance"
      }
      DynamicTest.dynamicTest("${provider?.name ?: "fixture"} $surface") {
        runInstalledSmoke(provider)
      }
    }
  }

  private fun runInstalledSmoke(liveProvider: ProviderKind?) {
    val pluginPath = Path.of(requireNotNull(System.getProperty("ideTest.pluginPath")
      ?: System.getProperty("path.to.build.plugin")))
    require(pluginPath.isAbsolute && Files.isRegularFile(pluginPath)) {
      "The installed plugin artifact must be an existing absolute ZIP path: $pluginPath"
    }
    val pluginBytes = Files.readAllBytes(pluginPath)
    val pluginHash = java.security.MessageDigest.getInstance("SHA-256")
      .digest(pluginBytes).joinToString("") { "%02x".format(it) }
    println("Installed plugin artifact: $pluginPath; bytes=${Files.size(pluginPath)}; SHA-256=$pluginHash")
    val fixturePath = Path.of(requireNotNull(javaClass.getResource("/autocomplete-project")).toURI())
    val projectPath = Files.createTempDirectory("subscription-autocomplete-$productCode-project-")
    val terminalFixture = TerminalSmokeFixture(projectPath)
    // Each product gets fresh project metadata; another IDE's module model is not portable.
    Files.list(fixturePath).use { files ->
      files.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".iml") }
        .forEach { Files.copy(it, projectPath.resolve(it.fileName)) }
    }
    val ideVersion = requireNotNull(System.getProperty("ideTest.ideVersion"))
    val provider = liveProvider ?: ProviderKind.CLAUDE
    if (liveProvider != null) {
      val profile = if (provider == ProviderKind.CLAUDE) ProviderPolicy.DEFAULT_CLAUDE_MODEL else {
        System.getProperty("ideTest.codexModel", ProviderPolicy.DEFAULT_CODEX_MODEL) + "/" +
          System.getProperty("ideTest.codexEffort", ProviderPolicy.DEFAULT_CODEX_EFFORT)
      }
      println("Installed live profile: ${provider.name}/$profile")
    }
    val executable = if (liveProvider == null) fakeClaudeExecutable(terminalFixture) else {
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
    val ideContext = Starter.newContext(
      testName = "subscriptionAutocomplete-${liveProvider?.name ?: "fixture"}-${System.nanoTime()}",
      testCase = TestCase(when (productCode) {
        "IU" -> IdeProductProvider.IU
        "PY" -> IdeProductProvider.PY
        "AI" -> IdeProductProvider.AI.copy(buildNumber = ideVersion)
        else -> error("Unsupported smoke-test product: $productCode")
      }.copy(getInstaller = { ExistingIdeInstaller(starterInstallation()) }), projectInfo = LocalProjectInfo(projectPath)),
    ).apply {
      if (productCode == "AI") {
        // Google's separate analytics consent modal blocks a fresh test JVM.
        // This built-in switch suppresses the prompt without writing an opt-in.
        ide.vmOptions.addSystemProperty("disable.android.analytics.consent.dialog", true)
        println("Android Studio test startup: analytics consent dialog disabled; no analytics opt-in written")
      }
      installedApplication = ide.installationPath
      preparedInstallationRoot?.toFile()?.deleteRecursively()
      preparedInstallationRoot = null
      // Starter opens ZIPs for writing and may delete its input on extraction
      // failure. Give it a disposable copy, preserving the supplied release ZIP.
      val installerArchive = Files.createTempFile("subscription-autocomplete-install-", ".zip")
      try {
        Files.write(installerArchive, pluginBytes)
        check(Files.mismatch(pluginPath, installerArchive) == -1L) {
          "The installer copy must match the supplied plugin artifact exactly"
        }
        PluginConfigurator(this).installPluginFromPath(installerArchive)
      } catch (failure: Throwable) {
        runCatching { Files.deleteIfExists(installerArchive) }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
      }
      Files.deleteIfExists(installerArchive)
      writeTestSettings(paths.configDir, executable, ideVersion, provider, liveProvider != null)
    }
    // Starter closes and joins the IDE before this outer gate reads flushed logs.
    // Returning from a terminal-only fixture does not bypass the outer finally.
    InstalledIdeRuntimeLogGate.afterIdeShutdown(ideContext.paths.testHome.resolve("log")) {
      ideContext.runIdeWithDriver(runTimeout = installedRunTimeout(liveProvider != null)).useDriverAndCloseIde {
        val productVersion = getProductVersion()
        check(productVersion.productCode == productCode) {
          "Expected $productCode, launched $productVersion"
        }
        check(productVersion.asString.removePrefix("$productCode-") == System.getProperty("ideTest.expectedBuild")) {
          "The running IDE must match the exact Gradle verification target: $productVersion"
        }
        println("Installed compatibility target: $productVersion")
        if (liveProvider == null) {
          withContext(OnDispatcher.EDT) {
            val settings = service(InstalledAutocompleteSettingsRef::class)
            val state = settings.snapshot()
            check(state.getEnabled() && !state.getManualOnly() && state.getAutomaticEngine() == "SELECTED_SUBSCRIPTION") {
              "A fresh installation must enable automatic subscription completion without saved settings"
            }
            println("Fresh installation: enabled=true; manualOnly=false; automaticEngine=SELECTED_SUBSCRIPTION; no prewritten plugin settings")
            // Configure only the fixture provider; retain the actual first-install automatic choice.
            state.setProvider(provider.name)
            state.setClaudeExecutable(executable.absolutePathString())
            settings.loadState(state)
          }
        }
        val terminalsOnly = System.getProperty("ideTest.terminalsOnly", "false").toBoolean()
        val reworkedOnly = System.getProperty("ideTest.reworkedOnly", "false").toBoolean()
        // Terminal input does not depend on indexing a fresh SDK/project.
        if (!terminalsOnly && !reworkedOnly) waitForIndicators(5.minutes)
        activateTestIde(installedApplication)
        ideFrame {
          toFront()
          ideStatusBar {
            val expectedActivity = if (liveProvider != null) "AI ⌨ hotkey" else "AI ○ idle"
            val expectedProvider = if (provider == ProviderKind.CLAUDE) "Claude" else "Codex"
            waitFor("installed plugin status widget ready", 60.seconds) {
              widgetStatusBarPanel.widgets.list().any { it.text.startsWith("$expectedActivity · $expectedProvider") }
            }
          }
        }
        takeScreenshot("compatibility-$productCode-ready")
        if (liveProvider == null) {
          if (!reworkedOnly) exerciseInstalledTerminal(installedApplication, terminalFixture)
          exerciseInstalledReworkedTerminal(installedApplication, terminalFixture)
          if (reworkedOnly || terminalsOnly) return@useDriverAndCloseIde
        }
        exerciseInstalledTyping(liveProvider != null)
        if (liveProvider == null) {
          activateTestIde(installedApplication)
          ideFrame { toFront() }
          invokeAction("ShowSettings", now = false)
          ideFrame {
            settingsDialog {
              com.intellij.driver.sdk.waitFor("settings dialog opened", 30.seconds) { present() }
              settingsTree.expandPath("Tools")
              val settingsRow = requireNotNull(settingsTree.findExpandedPath("Tools", "Claude/Codex Sub Autocomplete", fullMatch = true))
              driver.withContext(OnDispatcher.EDT) {
                cast(settingsTree.component, SettingsTreeRef::class).setSelectionRow(settingsRow.row)
              }
              content { waitContainsText("Enable Claude/Codex completions") }
              takeScreenshot("compatibility-$productCode-settings")
              cancelButton.click()
            }
          }
        }
      }
    }
  }

  private fun installedRunTimeout(live: Boolean): kotlin.time.Duration {
    val repetitions = if (live) 1 else System.getProperty("ideTest.repetitions", "3").toInt().coerceIn(1, 3)
    val externalEditorMinutes = if (System.getProperty("ideTest.externalEditorInput", "false").toBoolean()) {
      selectedTypingCases(live).size * 2
    } else 0
    // The full IDEA editor matrix takes about six minutes per pass. Reserve time
    // for startup, both terminal engines, external input, and Settings as well.
    return (10 + repetitions * (7 + externalEditorMinutes)).minutes
  }

  private fun starterInstallation(): Path {
    val platform = Path.of(requireNotNull(System.getProperty("ideTest.platformPath")))
    if (!System.getProperty("os.name").startsWith("Mac")) return platform
    if (platform.fileName.toString() == "Contents" && platform.parent.toString().endsWith(".app")) {
      return platform.parent
    }
    // Gradle normalizes macOS SDKs to Contents/. Starter needs a native .app bundle
    // and makes its own writable copy before installing the plugin and VM options.
    val root = Files.createTempDirectory("subscription-autocomplete-$productCode-sdk-")
    preparedInstallationRoot = root
    val app = root.resolve(when (productCode) {
      "AI" -> "Android Studio.app"
      "PY" -> "PyCharm.app"
      else -> "IntelliJ IDEA.app"
    })
    Files.createDirectories(app)
    val copy = ProcessBuilder("/usr/bin/ditto", platform.toString(), app.resolve("Contents").toString())
      .inheritIO().start()
    check(copy.waitFor() == 0) { "Could not prepare the resolved IDE for Starter" }
    // Gradle adds this marker to the SDK. Remove it only from our copy to restore
    // the vendor's sealed resource set before launching the signed native app.
    Files.deleteIfExists(app.resolve("Contents/Resources/.toolbox-ignore"))
    val signature = ProcessBuilder("/usr/bin/codesign", "--verify", "--deep", "--strict", app.toString())
      .inheritIO().start()
    check(signature.waitFor() == 0) { "The copied IDE must retain its vendor signature" }
    return app
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
    Files.writeString(optionsDirectory.resolve("terminal.xml"), """
      <application>
        <component name="TerminalOptionsProvider">
          <option name="terminalEngine" value="REWORKED" />
        </component>
      </application>
    """.trimIndent())
    if (live) Files.writeString(
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
    if (!live) check(Files.notExists(optionsDirectory.resolve("subscriptionAutocomplete.xml"))) {
      "Fixture installation must begin without a plugin settings file"
    }
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
              "trial.active.editor.tab.shown.versions": ["$productCode-$releaseLine"]
            }
          }]]></component>
        </application>
      """.trimIndent(),
    )
  }

  private fun com.intellij.driver.client.Driver.exerciseInstalledTyping(live: Boolean) {
    val project = singleProject()
    val repetitions = if (live) 1 else System.getProperty("ideTest.repetitions", "3").toInt().coerceIn(1, 3)
    repeat(repetitions) { repetition ->
      selectedTypingCases(live).forEachIndexed { caseIndex, case ->
        val automatic = !live && repetition == 0 && caseIndex == 0
        val externalEditorInput = System.getProperty("ideTest.externalEditorInput", "false").toBoolean() ||
          (automatic && System.getProperty("ideTest.externalTerminalInput", "false").toBoolean())
        val requirePhysicalTyping = automatic || externalEditorInput ||
          System.getProperty("ideTest.requirePhysicalTyping", "false").toBoolean()
        openFile(case.fileName, project)
        ideFrame {
          toFront()
          editorTabs { clickTab(case.fileName) }
          val editor = codeEditorForFile(case.fileName)
          val beforeTyping = case.prefix + case.suffix
          editor.text = beforeTyping
          editor.click()
          editor.moveCaretToOffset(case.prefix.length)
          editor.setFocus()
          val physicalTypingAvailable = externalEditorInput || (requirePhysicalTyping && editor.isFocusOwner())
          val physicalAcceptanceAvailable = physicalTypingAvailable && !externalEditorInput
          check(physicalTypingAvailable || !requirePhysicalTyping) {
            "Physical typing requires macOS Accessibility/input focus for the launched IntelliJ process"
          }
          val diagnosticsBeforeTyping = if (automatic) {
            service(ClassicPluginDiagnosticsLogRef::class).snapshot().map { it.toString() }.toSet()
          } else emptySet()
          if (externalEditorInput) {
            val expectedAfterTyping = case.prefix + case.typed + case.suffix
            takeScreenshot("compatibility-$productCode-${case.fileName}-typing-ready-${repetition + 1}")
            println("External editor input ready: product=$productCode; file=${case.fileName}; " +
              "repetition=${repetition + 1}; type=${com.google.gson.Gson().toJson(case.typed)}; acceptance=IDE API")
            com.intellij.driver.sdk.waitFor("external physical typing in ${case.fileName}", 120.seconds) {
              editor.text == expectedAfterTyping
            }
          } else if (physicalTypingAvailable) {
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
          if (!automatic) invokeAction(
            actionId = "SubscriptionAutocomplete.TriggerCompletion",
            now = true,
            component = editor.component,
            place = "SubscriptionAutocompleteIdeTest",
          )
          if (!live) takeScreenshot("compatibility-$productCode-${case.fileName}-requested")
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
          if (automatic) {
            check(suggestion == case.completion) {
              val json = com.google.gson.Gson()
              "Automatic suggestion must match this plugin's fixture output; " +
                "expected (${case.completion.length} characters)=${json.toJson(case.completion)}; " +
                "actual (${suggestion.length} characters)=${json.toJson(suggestion)}"
            }
            val newDiagnostics = service(ClassicPluginDiagnosticsLogRef::class).snapshot()
              .map { it.toString() }.filterNot { it in diagnosticsBeforeTyping }
            check(newDiagnostics.any { "Mode: automatic; provider: Claude Code subscription;" in it }) {
              "Typing must start an automatic request in this plugin before any completion hotkey"
            }
            println("Automatic fixture ${case.fileName}: typing produced ghost text without the completion hotkey")
          }
          if (!live) takeScreenshot("compatibility-$productCode-${case.fileName}-suggestion")
          assertTrue(
            documentsEqual(editor.text, case.prefix + case.typed + case.suffix),
            "Suggestion was inserted into ${case.fileName} before acceptance",
          )
          if (!live) {
            invokeAction("EditorEscape", now = true, component = editor.component)
            com.intellij.driver.sdk.waitFor("dismissed completion in ${case.fileName}", 5.seconds) {
              inlineCompletionText(editor, case.prefix.length + case.typed.length).isEmpty()
            }
            assertTrue(documentsEqual(editor.text, case.prefix + case.typed + case.suffix))
            invokeAction("SubscriptionAutocomplete.TriggerCompletion", now = true, component = editor.component)
            com.intellij.driver.sdk.waitFor("requested completion after dismissal", 10.seconds) {
              inlineCompletionText(editor, case.prefix.length + case.typed.length).isNotEmpty()
            }
          }
          if (live) {
            assertTrue(
              Regex("value\\s*\\*\\s*2|2\\s*\\*\\s*value|value\\s*\\+\\s*value")
                .containsMatchIn(suggestion),
              "Live completion did not implement doubling in ${case.fileName}",
            )
          }
          val expected = case.prefix + case.typed + (if (live) suggestion else case.completion) + case.suffix
          if (physicalAcceptanceAvailable) {
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
              "explicit acceptance preserved surrounding text; physical typing: $physicalTypingAvailable; " +
              "input: ${if (externalEditorInput) "external physical" else if (physicalTypingAvailable) "IDE Robot" else "IDE API"}; " +
              "acceptance: ${if (physicalAcceptanceAvailable) "physical Tab" else "IDE API"}",
          )
        }
      }
    }
  }

  private fun selectedTypingCases(live: Boolean): List<TypingCase> =
    (if (live) liveTypingCases else typingCases).filter { case ->
      when (productCode) {
        "PY" -> case.fileName in setOf("sample.py", "sample.sh", "sample.json", "sample.html")
        "AI" -> case.fileName in setOf("Sample.kt", "Sample.java", "sample.xml", "sample.json")
        else -> true
      }
    }

  private fun xmlAttribute(value: String): String = value.replace("&", "&amp;")
    .replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

  private fun fakeClaudeExecutable(terminalFixture: TerminalSmokeFixture): Path {
    val executable = Files.createTempFile("subscription-autocomplete-fake-claude-", ".sh")
    javaClass.getResourceAsStream("/fake-claude.sh").use { input ->
      requireNotNull(input) { "Missing fake Claude test executable" }
      val template = input.bufferedReader().readText()
      val commandJson = com.google.gson.Gson().toJson(terminalFixture.command).removeSurrounding("\"")
      Files.writeString(executable, template
        .replace("@TERMINAL_COMMAND_JSON@", shellQuote(commandJson))
        .replace("@TERMINAL_REQUEST_LOG@", shellQuote(terminalFixture.requestLog.toString())))
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

  private fun inlineCompletionText(editor: JEditorUiComponent, caretOffset: Int): String =
    editor.driver.withContext(OnDispatcher.EDT) {
      // The SDK inlay helper reads only the first line of multiline ghost text.
      // The displayed context retains every presentable element and its newlines.
      val context = utility(InstalledInlineCompletionContextCompanionRef::class).getOrNull(editor.editor)
        ?: return@withContext ""
      if (context.isDisposed() || !context.isCurrentlyDisplaying() || context.startOffset() != caretOffset) {
        ""
      } else {
        context.textToInsert()
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
    TypingCase("Sample.kt", "fun twice(value: Int): Int = val", "u", "e * 2", "\n"),
    TypingCase("docker-compose.yml", "# this wil", "l", " install dependencies", "\n"),
    TypingCase("sample.sql", "-- this wil", "l", " install dependencies", "\n"),
    TypingCase("sample.html", "<!-- this wil", "l", " install dependencies -->", "\n"),
    TypingCase("sample.xml", "<resources>\n    <string name=\"app_name\">Auto", "c", "omplete</string>", "\n</resources>\n"),
    TypingCase("sample.json", "{\n  \"private\": fals", "e", ",", "\n}\n"),
    TypingCase("Dockerfile", "# this wil", "l", " install dependencies", "\n"),
  )

  private val liveTypingCases = listOf(
    TypingCase(
      "Sample.kt",
      "// Return twice the input value.\nfun twice(value: Int): Int {\n    ",
      "return ", "value * 2", "\n}\n",
    ),
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

@Remote("com.intellij.codeInsight.inline.completion.session.InlineCompletionContext\$Companion")
internal interface InstalledInlineCompletionContextCompanionRef {
  fun getOrNull(editor: com.intellij.driver.sdk.Editor): InstalledInlineCompletionContextRef?
}

@Remote("com.intellij.codeInsight.inline.completion.session.InlineCompletionContext")
internal interface InstalledInlineCompletionContextRef {
  fun isDisposed(): Boolean
  fun isCurrentlyDisplaying(): Boolean
  fun startOffset(): Int?
  fun textToInsert(): String
}

@Remote("javax.swing.JTree")
internal interface SettingsTreeRef {
  fun setSelectionRow(row: Int)
}

@Remote("com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings", plugin = "com.kkoemets.subscriptionautocomplete")
internal interface InstalledAutocompleteSettingsRef {
  fun snapshot(): InstalledAutocompleteSettingsStateRef
  fun loadState(state: InstalledAutocompleteSettingsStateRef)
}

@Remote("com.kkoemets.subscriptionautocomplete.settings.AutocompleteSettings\$SettingsState", plugin = "com.kkoemets.subscriptionautocomplete")
internal interface InstalledAutocompleteSettingsStateRef {
  fun getEnabled(): Boolean
  fun getManualOnly(): Boolean
  fun getAutomaticEngine(): String
  fun setProvider(provider: String)
  fun setClaudeExecutable(executable: String)
}
