package com.kkoemets.subscriptionautocomplete.ide

import com.google.gson.Gson
import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.PluginId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Only primitive/local evidence crosses the process boundary. */
internal data class InstalledProcessIdentity(val name: String, val startTime: Long)

internal data class PersistedPluginSettings(
  val enabled: Boolean,
  val manualOnly: Boolean,
  val automaticEngine: String,
  val provider: String,
  val claudeExecutable: String,
  val terminalCompletionsEnabled: Boolean,
  val debounceMs: Int,
)

internal data class ScheduledPluginRestart(
  val before: InstalledProcessIdentity,
  val settings: PersistedPluginSettings,
  val version: String,
  val archive: Path,
  val archiveSha256: String,
  val installerCopy: Path,
  val firstTestHome: Path,
  val productCode: String,
  val targetBuild: String,
) : AutoCloseable {
  override fun close() { Files.deleteIfExists(installerCopy) }
}

/** Uses the same restart scheduler as Install from Disk; startup applies the queued archive. */
internal fun Driver.scheduleInstalledPluginRestart(
  archive: Path,
  archiveSha256: String,
  firstTestHome: Path,
): ScheduledPluginRestart {
  val product = getProductVersion()
  val before = installedProcessIdentity()
  val pluginId = utility(PluginId::class).getId("com.kkoemets.subscriptionautocomplete")
  val installerCopy = Files.createTempFile("subscription-autocomplete-restart-install-", ".zip")
  try {
    Files.copy(archive, installerCopy, StandardCopyOption.REPLACE_EXISTING)
    check(sha256(Files.readAllBytes(installerCopy)) == archiveSha256) { "Restart archive copy differs from candidate" }
    val settings = withContext(OnDispatcher.EDT) {
      val settingsService = service(InstalledAutocompleteSettingsRef::class)
      val state = settingsService.snapshot()
      // A nondefault persisted choice distinguishes restoration from fresh defaults.
      state.setDebounceMs(317)
      settingsService.loadState(state)
      installedSettingsSnapshot()
    }
    check(settings.claudeExecutable.isNotBlank() && settings.debounceMs == 317) {
      "Restart must preserve the configured fixture executable and a nondefault setting"
    }
    val version = withContext(OnDispatcher.IO) {
      val installed = requireNotNull(utility(ReloadPluginManagerRef::class).findPlugin(pluginId))
      check(installed.isRequireRestart()) { "Candidate descriptor must require an IDE restart" }
      check(!utility(ReloadDynamicPluginsRef::class).allowLoadUnloadWithoutRestart(installed)) {
        "The standard platform validator must reject restart-free modification"
      }
      val installedPath = installed.getPluginPath()
      writeInstalledPayloadManifest(archive, Path.of(installedPath.toString()), firstTestHome)
      val remoteArchive = new(ReloadFileRef::class, installerCopy.toString()).toPath()
      val descriptor = requireNotNull(utility(ReloadDescriptorLoaderRef::class).loadDescriptorFromArtifact(remoteArchive, null))
      check(descriptor.isRequireRestart() && descriptor.getVersion() == installed.getVersion()) {
        "Restart reinstall must use the same restart-required candidate"
      }
      // The Driver session retains File/Path/descriptor until the standard
      // installer persists its startup commands. The archive survives shutdown.
      withContext(OnDispatcher.EDT) {
        utility(ReloadPluginInstallerRef::class).installAfterRestart(descriptor, remoteArchive, installedPath, false)
        val installedState = utility(ReloadInstalledPluginsStateRef::class).getInstance()
        installedState.onPluginInstall(descriptor, true, true)
        check(installedState.wasUpdatedWithRestart(pluginId)) { "IDE did not record the scheduled restart update" }
      }
      val script = utility(ReloadPathManagerRef::class).getStartupScriptDir().resolve("action.script")
      check(utility(ReloadStartupActionScriptRef::class).loadActionScript(script)
        .any { installerCopy.toString() in it.toString() }) {
        "IDE startup queue must reference the exact candidate installer copy"
      }
      check(installed.getPluginClassLoader() != null && before == installedProcessIdentity()) {
        "Scheduling must leave the current plugin and IDE process running until shutdown"
      }
      installed.getVersion()
    }
    println("Installed restart scheduled: require-restart=true; platform refused restart-free modification; " +
      "standard installer queued candidate archive; current IDE process remains active")
    return ScheduledPluginRestart(before, settings, version, archive, archiveSha256, installerCopy,
      firstTestHome, product.productCode, product.asString)
  } catch (failure: Throwable) {
    Files.deleteIfExists(installerCopy)
    throw failure
  }
}

internal fun Driver.verifyInstalledPluginAfterRestart(
  scheduled: ScheduledPluginRestart,
  secondTestHome: Path,
): InstalledProcessIdentity {
  val product = getProductVersion()
  check(product.productCode == scheduled.productCode && product.asString == scheduled.targetBuild) {
    "Restart must use the identical IDE product/build"
  }
  val after = installedProcessIdentity()
  check(after.name != scheduled.before.name && after.startTime > scheduled.before.startTime) {
    "Restart evidence requires a new IDE process and later JVM start time"
  }
  withContext(OnDispatcher.IO) {
    val id = utility(PluginId::class).getId("com.kkoemets.subscriptionautocomplete")
    val descriptor = requireNotNull(utility(ReloadPluginManagerRef::class).findPlugin(id))
    check(descriptor.getVersion() == scheduled.version && descriptor.isRequireRestart() &&
      descriptor.getPluginClassLoader() != null) { "Restart did not load the expected candidate descriptor" }
    writeInstalledPayloadManifest(scheduled.archive, Path.of(descriptor.getPluginPath().toString()), secondTestHome)
    val script = utility(ReloadPathManagerRef::class).getStartupScriptDir().resolve("action.script")
    check(utility(ReloadStartupActionScriptRef::class).loadActionScript(script)
      .none { scheduled.installerCopy.toString() in it.toString() }) {
      "Startup did not consume the queued candidate installation"
    }
  }
  check(withContext(OnDispatcher.EDT) { installedSettingsSnapshot() } == scheduled.settings) {
    "The restarted IDE must restore saved provider, executable, automatic mode, terminal enablement and debounce"
  }
  println("Installed restart verified: new IDE process; exact candidate payload; saved provider executable and nondefault settings restored")
  return after
}

internal fun emitInstalledRestartEvidence(
  scheduled: ScheduledPluginRestart,
  after: InstalledProcessIdentity,
  secondTestHome: Path,
  terminal: RestartTerminalEvidence,
) {
  check(terminal.physicalTabRequests == 1L && !terminal.commandExecuted)
  println("Installed restart evidence: " + Gson().toJson(linkedMapOf(
    "productCode" to scheduled.productCode,
    "targetBuild" to scheduled.targetBuild,
    "beforeProcess" to scheduled.before.name,
    "afterProcess" to after.name,
    "beforeStartTime" to scheduled.before.startTime,
    "afterStartTime" to after.startTime,
    "archiveSha256" to scheduled.archiveSha256,
    "firstTestHome" to scheduled.firstTestHome.toString(),
    "secondTestHome" to secondTestHome.toString(),
    "restartRequired" to true,
    "dynamicModificationRefused" to true,
    "updateScheduled" to true,
    "settingsPersisted" to true,
    "physicalTabRequests" to terminal.physicalTabRequests,
    "commandExecuted" to terminal.commandExecuted,
  )))
}

private fun Driver.installedProcessIdentity(): InstalledProcessIdentity {
  val process = utility(ReloadManagementFactoryRef::class).getRuntimeMXBean()
  return InstalledProcessIdentity(process.getName(), process.getStartTime())
}

private fun Driver.installedSettingsSnapshot(): PersistedPluginSettings {
  val state = service(InstalledAutocompleteSettingsRef::class).snapshot()
  return PersistedPluginSettings(state.getEnabled(), state.getManualOnly(), state.getAutomaticEngine(),
    state.getProvider(), state.getClaudeExecutable(), state.getTerminalCompletionsEnabled(), state.getDebounceMs())
}

private fun writeInstalledPayloadManifest(archive: Path, installedPath: Path, launchHome: Path) {
  val expected = ZipFile(archive.toFile()).use { zip ->
    zip.entries().asSequence().filterNot { it.isDirectory }.associate { entry ->
      entry.name to sha256(zip.getInputStream(entry).use { it.readBytes() })
    }.toSortedMap()
  }
  val roots = expected.keys.map { it.substringBefore('/') }.toSet()
  check(roots.size == 1 && Files.isDirectory(installedPath)) { "Expected one installed plugin directory" }
  val root = roots.single()
  val actual = Files.walk(installedPath).use { files ->
    files.filter { Files.isRegularFile(it) }.toList().associate { file ->
      "$root/${installedPath.relativize(file).toString().replace('\\', '/')}" to sha256(Files.readAllBytes(file))
    }.toSortedMap()
  }
  check(actual == expected) { "Installed candidate files differ from the supplied archive: $installedPath" }
  Files.createDirectories(launchHome)
  Files.writeString(launchHome.resolve("installed-plugin-payload.json"), Gson().toJson(actual) + "\n")
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
  .digest(bytes).joinToString("") { "%02x".format(it) }

@Remote("com.intellij.ide.plugins.PluginManagerCore")
internal interface ReloadPluginManagerRef { fun findPlugin(id: PluginId): ReloadPluginDescriptorRef? }

@Remote("com.intellij.ide.plugins.PluginMainDescriptor")
internal interface ReloadPluginDescriptorRef {
  fun getVersion(): String
  fun isRequireRestart(): Boolean
  fun getPluginPath(): ReloadPathRef
  fun getPluginClassLoader(): ReloadClassLoaderRef?
}

@Remote("java.lang.ClassLoader")
internal interface ReloadClassLoaderRef

@Remote("com.intellij.ide.plugins.DynamicPlugins")
internal interface ReloadDynamicPluginsRef { fun allowLoadUnloadWithoutRestart(descriptor: ReloadPluginDescriptorRef): Boolean }

@Remote("com.intellij.ide.plugins.PluginInstaller")
internal interface ReloadPluginInstallerRef {
  fun installAfterRestart(descriptor: ReloadPluginDescriptorRef, archive: ReloadPathRef, existingPlugin: ReloadPathRef?, deleteSource: Boolean)
}

@Remote("com.intellij.ide.plugins.InstalledPluginsState")
internal interface ReloadInstalledPluginsStateRef {
  fun getInstance(): ReloadInstalledPluginsStateRef
  fun onPluginInstall(descriptor: ReloadPluginDescriptorRef, update: Boolean, restartRequired: Boolean)
  fun wasUpdatedWithRestart(id: PluginId): Boolean
}

@Remote("com.intellij.ide.plugins.PluginDescriptorLoader")
internal interface ReloadDescriptorLoaderRef {
  fun loadDescriptorFromArtifact(archive: ReloadPathRef, build: ReloadBuildNumberRef?): ReloadPluginDescriptorRef?
}

@Remote("com.intellij.openapi.util.BuildNumber")
internal interface ReloadBuildNumberRef

@Remote("java.io.File")
internal interface ReloadFileRef { fun toPath(): ReloadPathRef }

@Remote("java.nio.file.Path")
internal interface ReloadPathRef {
  fun resolve(path: String): ReloadPathRef
  override fun toString(): String
}

@Remote("com.intellij.openapi.application.PathManager")
internal interface ReloadPathManagerRef { fun getStartupScriptDir(): ReloadPathRef }

@Remote("com.intellij.ide.startup.StartupActionScriptManager")
internal interface ReloadStartupActionScriptRef { fun loadActionScript(script: ReloadPathRef): List<ReloadStartupCommandRef> }

@Remote("com.intellij.ide.startup.StartupActionScriptManager\$ActionCommand")
internal interface ReloadStartupCommandRef { override fun toString(): String }

@Remote("java.lang.management.ManagementFactory")
internal interface ReloadManagementFactoryRef { fun getRuntimeMXBean(): ReloadRuntimeBeanRef }

@Remote("java.lang.management.RuntimeMXBean")
internal interface ReloadRuntimeBeanRef {
  fun getName(): String
  fun getStartTime(): Long
}
