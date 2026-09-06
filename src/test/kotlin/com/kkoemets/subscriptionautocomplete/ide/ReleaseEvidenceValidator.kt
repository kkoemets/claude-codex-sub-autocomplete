package com.kkoemets.subscriptionautocomplete.ide

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Headless publication check. Archived reports are evidence, never instructions to execute. */
object ReleaseEvidenceValidator {
  private val tasks = mapOf(
    "IU" to "autocompleteInstalledIdeTest",
    "PY" to "autocompleteInstalledPyCharmTest",
    "AI" to "autocompleteInstalledAndroidStudioTest",
  )
  private const val suiteName = "com.kkoemets.subscriptionautocomplete.ide.InstalledPluginSmokeTest"
  private val sha256Pattern = Regex("[a-f0-9]{64}")
  private val immutableIdentityPattern = Regex("(?:[^\\s]+@)?sha256:[a-f0-9]{64}")

  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 7) {
      "Expected repository, releaseEvidenceDir, publication ZIP, current build ZIP, and IU/PY/AI=build"
    }
    val targets = args.drop(4).associate { value ->
      val parts = value.split('=', limit = 2)
      require(parts.size == 2 && parts[1].isNotBlank()) { "Invalid maintained IDE target: $value" }
      parts[0] to parts[1]
    }
    validate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), targets)
    println("Release IDE evidence passed: full IDEA, PyCharm, and Android Studio fixtures; " +
      "zero blocking IDE errors; current sources/builds; exact publication ZIP ${sha256(Path.of(args[2]))}")
  }

  fun validate(
    repository: Path,
    evidenceDirectory: Path,
    publicationZip: Path,
    currentBuildZip: Path,
    maintainedBuilds: Map<String, String>,
  ) {
    check(maintainedBuilds.keys == tasks.keys && maintainedBuilds.values.all { it.isNotBlank() }) {
      "Maintained builds must identify IDEA (IU), PyCharm (PY), and Android Studio (AI)"
    }
    val evidence = evidenceDirectory.toRealPath()
    val index = json(evidence.resolve("release-evidence.json"))
    check(index.get("schemaVersion")?.asInt == 1) { "Unsupported release evidence schema" }
    val runs = index.getAsJsonObject("runs") ?: error("Missing release evidence runs")
    check(runs.keySet() == tasks.keys) { "Release evidence requires exactly IU, PY, and AI runs" }
    val expectedHash = sha256(publicationZip)
    requireRestartDescriptor(publicationZip)
    val expectedPayload = zipPayload(publicationZip)
    check(expectedPayload == zipPayload(currentBuildZip)) {
      "Publication ZIP payload differs from the current buildPlugin output"
    }
    val currentSources = sourceHashes(repository)
    val validatedRuns = mutableMapOf<Path, String>()
    tasks.forEach { (product, task) ->
      val run = inside(evidence, runs.get(product).asString)
      val guestWorkspace = validatedRuns.getOrPut(run) { validateRun(run, currentSources, expectedHash) }
      validateProduct(run, product, task, maintainedBuilds.getValue(product), expectedHash, expectedPayload, guestWorkspace)
    }
  }

  private fun validateRun(run: Path, currentSources: Map<String, String>, expectedHash: String): String {
    val manifestPath = inside(run, "source-manifest.json")
    val manifest = json(manifestPath)
    val archivedSources = manifest.entrySet().filter { sourceInput(it.key) }
      .associate { it.key to it.value.asString }
    check(archivedSources == currentSources) {
      "Stale or incomplete source manifest in $run; changed inputs: " +
        (archivedSources.keys + currentSources.keys).filter { archivedSources[it] != currentSources[it] }
          .sorted().joinToString(", ")
    }
    val work = inside(run, "work")
    archivedSources.forEach { (name, expected) ->
      check(sha256(inside(work, name)) == expected) { "Archived source snapshot differs from its manifest: $name" }
    }
    val artifact = inside(run, "artifact/plugin.zip")
    check(sha256(artifact) == expectedHash) { "Stale artifact in $run: ZIP differs from publication bytes" }
    check(Files.readString(inside(run, "artifact/SHA256SUMS")).trim() == "$expectedHash  plugin.zip") {
      "Artifact checksum receipt does not match publication ZIP in $run"
    }
    // Existing Docker archives remain valid without a runner receipt. A present but invalid
    // native receipt must never fall back to a successful Docker final-state record.
    if (Files.exists(run.resolve("isolated-runner.json"), LinkOption.NOFOLLOW_LINKS)) {
      return validateTartRunner(run, sha256(manifestPath), expectedHash)
    }
    val containers = JsonParser.parseString(Files.readString(inside(run, "container-final.json"))).asJsonArray
    check(containers.size() == 1) { "Expected one completed isolated container in $run" }
    val state = containers[0].asJsonObject.getAsJsonObject("State")
    check(state.get("Status")?.asString == "exited" && state.get("ExitCode")?.asInt == 0 &&
      state.get("Running")?.asBoolean == false && state.get("OOMKilled")?.asBoolean == false) {
      "Isolated run did not finish successfully in $run"
    }
    return "/work"
  }

  private fun validateTartRunner(run: Path, manifestHash: String, artifactHash: String): String {
    val runner = json(inside(run, "isolated-runner.json"))
    check(integer(runner, "schemaVersion") == 1 && string(runner, "kind") == "tart") {
      "Unsupported isolated runner receipt in $run"
    }
    val guestWorkspace = string(runner, "guestWorkspace").orEmpty()
    check(canonicalGuestPath(guestWorkspace)) { "Unsafe guest workspace in $run: $guestWorkspace" }
    check(integer(runner, "guestCommandExitCode") == 0 && integer(runner, "hostTransportExitCode") == 0 &&
      trueBoolean(runner, "completed")) { "Tart isolated run did not finish successfully in $run" }
    check(string(runner, "sourceManifestSha256") == manifestHash &&
      string(runner, "artifactSha256") == artifactHash) { "Tart runner source or artifact digest differs in $run" }
    val vmName = string(runner, "vmName").orEmpty()
    val imageIdentity = string(runner, "imageIdentity").orEmpty()
    check(vmName.isNotBlank() && !vmName.startsWith('-') && vmName.none { it.isISOControl() } &&
      immutableIdentityPattern.matches(imageIdentity)) {
      "Missing or invalid Tart VM or immutable image identity in $run"
    }
    val identityPath = inside(run, "prepared-vm-identity.json")
    check(imageIdentity == "sha256:${sha256(identityPath)}") { "Tart prepared VM identity digest differs in $run" }
    val identity = json(identityPath)
    val files = identity.get("files")?.takeIf { it.isJsonObject }?.asJsonObject
    check(integer(identity, "schemaVersion") == 1 && string(identity, "vmName") == vmName &&
      immutableIdentityPattern.matches(string(identity, "baseImageIdentity").orEmpty()) &&
      files != null && files.keySet() == setOf("config.json", "disk.img", "nvram.bin") &&
      files.entrySet().all { (_, value) ->
        value.isJsonPrimitive && value.asJsonPrimitive.isString && sha256Pattern.matches(value.asString)
      }) { "Invalid Tart prepared VM identity in $run" }
    val argv = runner.get("launchArgv")?.takeIf { it.isJsonArray }?.asJsonArray?.map { value ->
      value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    }.orEmpty()
    val isolationFlags = setOf("--no-graphics", "--no-audio", "--no-clipboard")
    val forbiddenFlags = setOf("--vnc", "--vnc-experimental", "--capture-system-keys")
    check(argv.size >= 6 && argv.all { value -> value.isNotBlank() && value.none { it.isISOControl() } } &&
      argv.first().substringAfterLast('/') == "tart" && argv[1] == "run" &&
      argv.drop(2).count { it == vmName } == 1 && isolationFlags.all { flag -> argv.count { it == flag } == 1 } &&
      tartLaunchNamesVm(argv, vmName, isolationFlags) &&
      argv.none { argument -> argument == "--" || argument.substringBefore('=') in forbiddenFlags ||
        (argument.substringBefore('=') in isolationFlags && '=' in argument) }) {
      "Tart launch argv must identify the VM and disable host graphics, audio, and clipboard in $run"
    }
    val guest = json(inside(run, "guest-completion.json"))
    check(integer(guest, "schemaVersion") == 1 && string(guest, "guestWorkspace") == guestWorkspace &&
      string(guest, "sourceManifestSha256") == manifestHash && string(guest, "artifactSha256") == artifactHash &&
      integer(guest, "guestCommandExitCode") == 0 && trueBoolean(guest, "completed")) {
      "Tart guest completion receipt is incomplete or differs from its host receipt in $run"
    }
    return guestWorkspace
  }

  private fun tartLaunchNamesVm(argv: List<String>, vmName: String, isolationFlags: Set<String>): Boolean {
    var index = 2
    var foundVm = false
    while (index < argv.size) {
      when (argv[index++]) {
        in isolationFlags -> Unit
        "--dir" -> {
          if (index == argv.size || argv[index].startsWith('-')) return false
          index++
        }
        vmName -> if (foundVm) return false else foundVm = true
        // Only the flags emitted by the isolated runner are accepted. Parsing --dir values
        // prevents a matching directory argument from masquerading as the VM operand.
        else -> return false
      }
    }
    return foundVm
  }

  private fun string(value: JsonObject, field: String): String? = value.get(field)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

  private fun integer(value: JsonObject, field: String): Int? = value.get(field)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()

  private fun trueBoolean(value: JsonObject, field: String): Boolean = value.get(field)
    ?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true

  private fun canonicalGuestPath(value: String): Boolean = value.startsWith('/') && value != "/" &&
    '\\' !in value && value.none { it.isISOControl() } &&
    value.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }

  private fun guestRelativePath(guestWorkspace: String, absolute: String): String {
    check(canonicalGuestPath(absolute) && absolute.startsWith("$guestWorkspace/")) {
      "Installed evidence path is outside its guest workspace: $absolute"
    }
    return absolute.removePrefix("$guestWorkspace/")
  }

  private fun validateProduct(
    run: Path,
    product: String,
    task: String,
    expectedBuild: String,
    expectedHash: String,
    expectedPayload: Map<String, String>,
    guestWorkspace: String,
  ) {
    val work = inside(run, "work")
    val report = inside(work, "build/test-results/$task/TEST-$suiteName.xml")
    val root = xmlFactory().newDocumentBuilder().parse(report.toFile()).documentElement
    check(root.tagName == "testsuite" && root.getAttribute("name") == suiteName &&
      root.getAttribute("tests") == "1" &&
      listOf("failures", "errors", "skipped").all { root.getAttribute(it) == "0" }) {
      "Missing, failed, skipped, or incomplete installed fixture suite: $report"
    }
    val cases = root.getElementsByTagName("testcase")
    check(cases.length == 1 && cases.item(0).attributes.getNamedItem("name")?.nodeValue ==
      "fixture ghost text requires acceptance" &&
      listOf("failure", "error", "skipped").all { root.getElementsByTagName(it).length == 0 }) {
      "Release requires the full fixture editor and both terminal engines, without skipped checks: $report"
    }
    val output = root.getElementsByTagName("system-out")
    check(output.length == 1) { "Missing installed fixture stdout: $report" }
    val stdout = output.item(0).textContent
    fun uniqueValue(pattern: Regex): String {
      val values = pattern.findAll(stdout).map { it.groupValues[1] }.toSet()
      check(values.size == 1) { "Missing or ambiguous installed evidence ${pattern.pattern}: $report" }
      return values.single()
    }
    val actualBuild = uniqueValue(Regex("(?m)^Installed compatibility target: ([^\\r\\n]+)$"))
    check(actualBuild == "$product-${expectedBuild.removePrefix("$product-")}") {
      "Stale maintained IDE build in $report: $actualBuild; expected $product-$expectedBuild"
    }
    val actualHash = uniqueValue(Regex("(?m)^Installed plugin artifact: .+; bytes=\\d+; SHA-256=([a-f0-9]{64})$"))
    check(actualHash == expectedHash) { "Installed fixture tested a different ZIP: $report" }
    listOf(
      "classic terminal: physical Tab replaced the request; editable command remained unexecuted; explicit Enter created the sentinel",
      "Reworked terminal: physical Tab replaced the request; editable command remained unexecuted; explicit Enter created the sentinel",
      "Classic running-program: exactly one Tab byte reached the child; no provider request",
      "Classic alternate-program: exactly one Tab byte reached the child; no provider request",
      "Reworked running-program: exactly one Tab byte reached the child; no provider request",
      "Reworked alternate-program: exactly one Tab byte reached the child; no provider request",
      "Reworked search: Find handled physical Tab; terminal input unchanged; no provider request",
      "Fresh installation: enabled=true; manualOnly=false; automaticEngine=SELECTED_SUBSCRIPTION; no prewritten plugin settings",
    ).forEach { marker -> check(marker in stdout) { "Incomplete installed fixture coverage ($marker): $report" } }
    check(Regex("(?m)^Automatic fixture .+: initial automatic suggestion accepted directly; acceptance: physical Tab$")
      .containsMatchIn(stdout)) { "Initial automatic suggestion must be accepted using physical Tab: $report" }
    check(Regex("(?m)^Fixture .+: ghost text displayed; explicit acceptance preserved surrounding text;")
      .containsMatchIn(stdout)) { "Missing editor acceptance coverage: $report" }
    val guestTestHome = uniqueValue(Regex("(?m)^Installed fixture home: ([^\\r\\n]+)$"))
    val testHomePath = guestRelativePath(guestWorkspace, guestTestHome)
    check(testHomePath.startsWith("out/ide-tests/tests/$product-")) {
      "Installed fixture log directory belongs to a different product: $testHomePath"
    }
    val testHome = inside(work, testHomePath)
    val restart = JsonParser.parseString(uniqueValue(
      Regex("(?m)^Installed restart evidence: (\\{[^\\r\\n]*})$"),
    )).asJsonObject
    check(restart.get("productCode")?.asString == product && restart.get("targetBuild")?.asString == actualBuild &&
      restart.get("archiveSha256")?.asString == expectedHash) { "Restart evidence targets a different product, build, or archive: $report" }
    listOf("restartRequired", "dynamicModificationRefused", "updateScheduled", "settingsPersisted").forEach { field ->
      check(restart.get(field)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) {
        "Missing installer/restart proof ($field): $report"
      }
    }
    check(restart.get("physicalTabRequests")?.asInt == 1 &&
      restart.get("commandExecuted")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && !it.asBoolean } == true) {
      "Restart must preserve settings and prove one physical terminal Tab request without executing the command: $report"
    }
    val beforeProcess = restart.get("beforeProcess")?.asString.orEmpty()
    val afterProcess = restart.get("afterProcess")?.asString.orEmpty()
    val beforeStart = restart.get("beforeStartTime")?.asLong ?: 0L
    val afterStart = restart.get("afterStartTime")?.asLong ?: 0L
    check(beforeProcess.isNotBlank() && afterProcess.isNotBlank() && beforeProcess != afterProcess &&
      beforeStart > 0 && afterStart > beforeStart) { "Restart evidence must prove a fresh IDE process: $report" }
    val sessions = listOf("firstTestHome", "secondTestHome").map { field ->
      val path = restart.get(field)?.asString.orEmpty()
      check(path.startsWith("$guestTestHome/")) { "Restart session does not belong to the candidate fixture ($field): $report" }
      inside(work, guestRelativePath(guestWorkspace, path))
    }
    check(sessions.distinct().size == 2) { "Restart evidence must preserve both session archives: $report" }
    sessions.forEach { session ->
      // Each launch has its own retained log tree, including rotations and shutdown errors.
      val scan = InstalledIdeRuntimeLogGate.scan(inside(session, "log"), actualBuild)
      scan.acceptedPlatformErrors.forEach { println("Accepted stock Android terminal error: ${it.summary()}") }
      scan.requireNoBlockingErrors()
      val payload = json(inside(session, "installed-plugin-payload.json")).entrySet()
        .associate { it.key to it.value.asString }
      check(payload == expectedPayload) { "Restart session installed payload differs from publication ZIP: $session" }
    }
    val pluginRoot = expectedPayload.keys.map { it.substringBefore('/') }.toSet().single()
    val plugins = inside(testHome, "plugins")
    val installedRoot = inside(plugins, pluginRoot)
    val installedPayload = Files.walk(installedRoot).use { paths ->
      paths.filter(Files::isRegularFile).toList().associate { file ->
        val relative = plugins.relativize(file).toString().replace('\\', '/')
        relative to sha256(inside(plugins, relative))
      }
    }
    check(installedPayload == expectedPayload) { "Installed plugin payload differs from publication ZIP: $testHome" }
  }

  private fun sourceHashes(repository: Path): Map<String, String> {
    val process = ProcessBuilder("git", "-C", repository.toString(), "ls-files", "--cached", "--others",
      "--exclude-standard", "-z").redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val names = process.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000')
    check(process.waitFor() == 0) { "Could not list current repository source inputs" }
    val sources = names.filter(::sourceInput).distinct().filter { Files.exists(repository.resolve(it)) }
      .associateWith { sha256(inside(repository, it)) }
    check(sources.keys.any { it.startsWith("src/main/") } && "build.gradle.kts" in sources &&
      "gradle.properties" in sources) { "Current repository source inputs are incomplete" }
    return sources
  }

  private fun sourceInput(name: String): Boolean =
    listOf("src/", "scripts/", "gradle/").any(name::startsWith) ||
      name in setOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")

  private fun json(path: Path): JsonObject = JsonParser.parseString(Files.readString(path)).asJsonObject

  private fun xmlFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    setFeature("http://xml.org/sax/features/external-general-entities", false)
    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
  }

  private fun requireRestartDescriptor(archive: Path) {
    val descriptors = mutableListOf<org.w3c.dom.Element>()
    ZipFile(archive.toFile()).use { zip ->
      zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".jar") }.forEach { jar ->
        ZipInputStream(zip.getInputStream(jar)).use { entries ->
          while (true) {
            val entry = entries.nextEntry ?: break
            if (!entry.isDirectory && entry.name == "META-INF/plugin.xml") {
              val root = xmlFactory().newDocumentBuilder().parse(entries.readBytes().inputStream()).documentElement
              val ids = root.getElementsByTagName("id")
              if (root.tagName == "idea-plugin" && ids.length == 1 &&
                ids.item(0).textContent.trim() == "com.kkoemets.subscriptionautocomplete") descriptors += root
            }
          }
        }
      }
    }
    check(descriptors.size == 1 && descriptors.single().getAttribute("require-restart") == "true") {
      "Publication ZIP must contain exactly one plugin descriptor with require-restart=true"
    }
  }

  private fun inside(root: Path, relative: String): Path {
    val path = Path.of(relative)
    check(!path.isAbsolute && path.none { it.toString() == ".." }) { "Unsafe archived evidence path: $relative" }
    val canonicalRoot = root.toRealPath()
    val resolved = canonicalRoot.resolve(path).toRealPath()
    check(resolved.startsWith(canonicalRoot)) { "Archived evidence path escapes its directory: $relative" }
    return resolved
  }

  internal fun sha256(path: Path): String {
    check(Files.isRegularFile(path)) { "Missing evidence file: $path" }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(65536)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun zipPayload(path: Path): Map<String, String> = ZipFile(path.toFile()).use { zip ->
    val payload = linkedMapOf<String, String>()
    zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
      val entryPath = Path.of(entry.name)
      check(!entryPath.isAbsolute && entryPath.none { it.toString() == ".." } &&
        '/' in entry.name && '\\' !in entry.name && entry.name !in payload) { "Invalid ZIP payload path: ${entry.name}" }
      payload[entry.name] = zip.getInputStream(entry).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
      }
    }
    check(payload.isNotEmpty() && payload.values.all(sha256Pattern::matches) &&
      payload.keys.map { it.substringBefore('/') }.toSet().size == 1) { "Incomplete plugin ZIP payload: $path" }
    payload
  }
}
