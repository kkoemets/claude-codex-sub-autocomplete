package com.kkoemets.subscriptionautocomplete.ide

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.PluginId
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.minutes

internal data class InstalledIdeTrialBootstrap(
  val productCode: String,
  val generatedKey: Path,
  val setupLogDirectory: Path,
) {
  fun copyInto(candidateConfigDirectory: Path) {
    check(!Files.exists(candidateConfigDirectory.resolve("options/subscriptionAutocomplete.xml"))) {
      "The candidate must retain a fresh plugin installation without saved plugin settings"
    }
    check(Files.isRegularFile(generatedKey) && Files.size(generatedKey) > 0) {
      "IDE first-run setup did not generate its trial state"
    }
    Files.createDirectories(candidateConfigDirectory)
    val destination = candidateConfigDirectory.resolve(generatedKey.fileName)
    check(!Files.exists(destination)) { "Candidate trial state must come only from this guest's setup" }
    Files.copy(generatedKey, destination, StandardCopyOption.COPY_ATTRIBUTES)
    check(Files.mismatch(generatedKey, destination) == -1L) { "Guest trial state copy changed" }
    println("Installed trial setup: copied only this guest's IDE-generated trial file; plugin settings remain absent")
  }

  fun assertCandidateReady(driver: Driver) {
    check(driver.getProductVersion().productCode == productCode) { "Candidate must use the bootstrapped IDE product" }
    val ultimate = driver.utility(PluginId::class).getId("com.intellij.modules.ultimate")
    check(driver.utility(TrialBootstrapPluginManagerRef::class).findPlugin(ultimate)
      ?.let { it.isEnabled() && it.getPluginClassLoader() != null } == true) {
      "Candidate must start with Ultimate already loaded; first-run licensing must not leave a pending plugin restart"
    }
    // The remote driver can become available during the splash screen, before
    // LicensingFacade is initialized. Verify startup plugin loading first, then
    // await the evaluation service reading the guest-generated trial state.
    waitFor("bootstrapped $productCode evaluation service readiness", 1.minutes) {
      driver.utility(TrialBootstrapLicensingRef::class).getInstance()?.isEvaluationLicense() == true
    }
    println("Installed trial restart: $productCode evaluation active; Ultimate already loaded before candidate checks")
  }
}

/**
 * IDEA 262.10315.125 initially starts in free mode, then enables Ultimate when its
 * normal trial activates. The platform profiler extensionAdded listener creates
 * Windows/DTrace states on Linux without checking isAvailable; shutdown cannot
 * serialize those unavailable states. PyCharm 262.9437.214 cannot dynamically
 * load its Python Docker module's non-dynamic eelFilter extension after trial
 * activation, leaving a pending restart that prevents subsequent plugin unload.
 * Initialize either stock-IDE lifecycle in a separate profile, then start the
 * candidate with its evaluation and Ultimate already active after restart.
 * Preserve all setup errors as a platform baseline; candidate logs stay strict.
 */
internal fun bootstrapIsolatedIdeTrial(
  productCode: String,
  installation: Path,
  expectedBuild: String,
): InstalledIdeTrialBootstrap {
  check(System.getProperty("os.name") == "Linux" && System.getProperty("user.home") == "/home/tester") {
    "IDE trial setup is restricted to the isolated Linux guest; never use host account state"
  }
  val product = when (productCode) {
    "IU" -> IdeProductProvider.IU
    "PY" -> IdeProductProvider.PY
    else -> error("Trial bootstrap is supported only for IDEA and PyCharm: $productCode")
  }
  val keyFileName = if (productCode == "IU") "idea.key" else "pycharm.key"
  val productName = if (productCode == "IU") "IDEA" else "PyCharm"
  val project = Files.createTempDirectory("subscription-ide-trial-setup-")
  Files.writeString(project.resolve("README.txt"), "Isolated IDE first-run setup; completion plugin is absent.\n")
  val context = Starter.newContext(
    testName = "subscriptionIdeTrialSetup-$productCode-${System.nanoTime()}",
    testCase = TestCase(
      product.copy(getInstaller = { ExistingIdeInstaller(installation) }),
      projectInfo = LocalProjectInfo(project),
    ),
  )
  val setupLogs = context.paths.testHome.resolve("log")
  val pluginSettings = context.paths.configDir.resolve("options/subscriptionAutocomplete.xml")
  check(!Files.exists(pluginSettings)) { "Unexpected completion-plugin state in first-run IDE setup" }
  println("Installed trial setup home: ${context.paths.testHome}")
  context.runIdeWithDriver(runTimeout = 7.minutes).useDriverAndCloseIde {
    val target = getProductVersion()
    check(target.productCode == productCode &&
      target.asString.removePrefix("$productCode-") == expectedBuild.removePrefix("$productCode-")) {
      "First-run setup must use the exact candidate IDE build: $target"
    }
    val plugins = utility(TrialBootstrapPluginManagerRef::class)
    val completionPlugin = utility(PluginId::class).getId("com.kkoemets.subscriptionautocomplete")
    check(plugins.findPlugin(completionPlugin) == null) { "Completion plugin must be absent during stock-IDE setup" }
    waitFor("stock IDE first-run project frame", 3.minutes) { ideFrame().present() }
    val ultimate = utility(PluginId::class).getId("com.intellij.modules.ultimate")
    waitFor("normal $productName trial activation", 5.minutes) {
      utility(TrialBootstrapLicensingRef::class).getInstance()?.isEvaluationLicense() == true &&
        (productCode == "PY" ||
          plugins.findPlugin(ultimate)?.let { it.isEnabled() && it.getPluginClassLoader() != null } == true)
    }
    waitForIndicators(3.minutes)
    check(plugins.findPlugin(completionPlugin) == null) { "Completion plugin appeared during stock-IDE setup" }
    println(if (productCode == "IU") {
      "Installed trial setup: IDE activated its normal evaluation and loaded Ultimate; completion plugin absent"
    } else {
      "Installed trial setup: PyCharm activated its normal evaluation; Ultimate loading deferred to normal restart; completion plugin absent"
    })
  }
  check(!Files.exists(pluginSettings)) { "First-run setup created unexpected completion-plugin settings" }
  val key = context.paths.configDir.resolve(keyFileName)
  check(Files.isRegularFile(key) && Files.size(key) > 0) { "Normal IDE trial activation did not save $keyFileName" }
  val scan = InstalledIdeRuntimeLogGate.scan(setupLogs)
  Files.writeString(context.paths.testHome.resolve("platform-setup-findings.txt"), buildString {
    appendLine("Stock $productName first-run setup, before the completion plugin is installed.")
    appendLine("Target: $productCode-${expectedBuild.removePrefix("$productCode-")}")
    appendLine("Normal evaluation activated; IDE closed normally before candidate startup.")
    appendLine("All setup logs are preserved; these are not candidate release-pass evidence.")
    if (productCode == "IU") {
      appendLine("Known platform cause: dynamic profiler extensionAdded creates unavailable Windows/DTrace states on Linux;")
      appendLine("writeConfigurationSafely resolves only availableEPs and logs errors when saving those states.")
    } else {
      appendLine("Known platform cause: intellij.python.docker uses the non-dynamic eelFilter extension point;")
      appendLine("first-run evaluation activation cannot load Ultimate dynamically and needs a normal IDE restart.")
    }
    appendLine("Scanned files: ${scan.scannedFiles.size}; error records: ${scan.pluginErrors.size + scan.unrelatedErrors.size}")
    (scan.pluginErrors + scan.unrelatedErrors).forEach { appendLine(it.summary()) }
  })
  println("Installed trial setup: closed normally; preserved ${scan.pluginErrors.size + scan.unrelatedErrors.size} " +
    "baseline IDE error records at $setupLogs; candidate runtime log gate remains strict")
  return InstalledIdeTrialBootstrap(productCode, key, setupLogs)
}

@Remote("com.intellij.ide.plugins.PluginManagerCore")
internal interface TrialBootstrapPluginManagerRef {
  fun findPlugin(id: PluginId): TrialBootstrapPluginDescriptorRef?
}

@Remote("com.intellij.ide.plugins.PluginMainDescriptor")
internal interface TrialBootstrapPluginDescriptorRef {
  fun isEnabled(): Boolean
  fun getPluginClassLoader(): TrialBootstrapClassLoaderRef?
}

@Remote("java.lang.ClassLoader")
internal interface TrialBootstrapClassLoaderRef

@Remote("com.intellij.ui.LicensingFacade")
internal interface TrialBootstrapLicensingRef {
  fun getInstance(): TrialBootstrapLicensingRef?
  fun isEvaluationLicense(): Boolean
}
