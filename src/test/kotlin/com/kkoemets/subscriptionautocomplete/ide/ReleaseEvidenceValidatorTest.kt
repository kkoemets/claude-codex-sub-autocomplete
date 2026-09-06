package com.kkoemets.subscriptionautocomplete.ide

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ReleaseEvidenceValidatorTest {
  @Test
  fun `accepts one completed current full matrix bound to the publication ZIP`() = withFixture { it.validate() }

  @Test
  fun `missing index or product report cannot pass`() = withFixture { fixture ->
    Files.delete(fixture.report("AI"))
    fixture.rejected()
    Files.delete(fixture.evidence.resolve("release-evidence.json"))
    fixture.rejected()
  }

  @Test
  fun `incomplete product matrix cannot pass`() = withFixture { fixture ->
    fixture.write(fixture.evidence.resolve("release-evidence.json"),
      """{"schemaVersion":1,"runs":{"IU":"run1","PY":"run1"}}""")
    fixture.rejected("requires exactly IU, PY, and AI")
  }

  @Test
  fun `failed skipped and terminal-only fixtures cannot pass`() = withFixture { fixture ->
    val report = fixture.report("IU")
    val original = Files.readString(report)
    listOf(
      original.replace("failures=\"0\"", "failures=\"1\""),
      original.replace("skipped=\"0\"", "skipped=\"1\""),
      original.replace("fixture ghost text requires acceptance", "fixture terminal regression"),
      original.replace("fixture ghost text requires acceptance", "fixture Reworked terminal review"),
      original.replace("/>", "><skipped/></testcase>")
    ).forEach { invalid ->
      fixture.write(report, invalid)
      fixture.rejected()
    }
  }

  @Test
  fun `stale maintained IDE build and artifact reports cannot pass`() = withFixture { fixture ->
    fixture.rejected("Stale maintained IDE build", builds = fixture.builds + ("PY" to "262.9999.1"))
    fixture.replaceReport("PY", "SHA-256=${fixture.hash}", "SHA-256=${"0".repeat(64)}")
    fixture.rejected("tested a different ZIP")
  }

  @Test
  fun `changed source and added runtime input invalidate the archived manifest`() = withFixture { fixture ->
    fixture.write(fixture.repo.resolve("src/main/example.kt"), "changed production code")
    fixture.rejected("Stale or incomplete source manifest")
    fixture.write(fixture.repo.resolve("src/main/example.kt"), "production code")
    fixture.write(fixture.repo.resolve("src/test/new-test.kt"), "new coverage")
    fixture.rejected("Stale or incomplete source manifest")
  }

  @Test
  fun `documentation-only changes do not invalidate unchanged runtime evidence`() = withFixture { fixture ->
    fixture.write(fixture.repo.resolve("RELEASING.md"), "New publication instructions")
    fixture.validate()
  }

  @Test
  fun `tampered source snapshot cannot borrow a valid manifest`() = withFixture { fixture ->
    fixture.write(fixture.run.resolve("work/src/main/example.kt"), "different tested code")
    fixture.rejected("Archived source snapshot differs")
  }

  @Test
  fun `different ZIP bytes invalidate a receipt even when the payload matches`() = withFixture { fixture ->
    fixture.zip(fixture.publication, "plugin bytes", comment = "re-signed archive")
    fixture.rejected("ZIP differs from publication bytes")
  }

  @Test
  fun `publication payload must match the current build`() = withFixture { fixture ->
    fixture.zip(fixture.currentBuild, "changed plugin bytes")
    fixture.rejected("differs from the current buildPlugin output")
  }

  @Test
  fun `every installed payload file and unexpected extra file are checked`() = withFixture { fixture ->
    val installed = fixture.testHome("AI").resolve("plugins/plugin/lib/plugin.jar")
    fixture.write(installed, "wrong installed bytes")
    fixture.rejected("Installed plugin payload differs")
    Files.write(installed, fixture.jarBytes("plugin bytes"))
    fixture.write(installed.resolveSibling("unexpected.jar"), "extra jar")
    fixture.rejected("Installed plugin payload differs")
  }

  @Test
  fun `platform errors are rescanned even when the JUnit says success`() = withFixture { fixture ->
    listOf("before-restart", "after-restart").forEach { phase ->
      val log = fixture.sessionHome("PY", phase).resolve("log/idea.log")
      fixture.write(log, "SEVERE - #com.intellij.platform.Platform - unrelated platform error\n")
      fixture.rejected("runtime errors")
      fixture.write(log, "INFO - #com.intellij.idea.Main - IDE started and stopped\n")
    }
  }

  @Test
  fun `missing runtime logs cannot pass`() = withFixture { fixture ->
    Files.delete(fixture.sessionHome("PY", "before-restart").resolve("log/idea.log"))
    fixture.rejected("Missing or empty installed-IDE runtime log")
  }

  @Test
  fun `missing initial automatic physical Tab or terminal coverage cannot pass`() = withFixture { fixture ->
    val report = fixture.report("IU")
    val original = Files.readString(report)
    fixture.write(report, original.replace("accepted directly; acceptance: physical Tab", "accepted directly; acceptance: IDE API"))
    fixture.rejected("Initial automatic suggestion")
    fixture.write(report, original.replace("Classic running-program: exactly one Tab byte", "Classic running-program: missing Tab byte"))
    fixture.rejected("Incomplete installed fixture coverage")
  }

  @Test
  fun `failed or unfinished isolated containers cannot pass`() = withFixture { fixture ->
    fixture.write(fixture.run.resolve("container-final.json"),
      """[{"State":{"Status":"exited","ExitCode":1,"Running":false,"OOMKilled":false}}]""")
    fixture.rejected("did not finish successfully")
    fixture.write(fixture.run.resolve("container-final.json"),
      """[{"State":{"Status":"running","ExitCode":0,"Running":true,"OOMKilled":false}}]""")
    fixture.rejected("did not finish successfully")
  }

  @Test
  fun `accepts complete Tart matrix with run-specific guest paths and unchanged archived reports`() = withFixture { fixture ->
    fixture.useTart()
    val reports = fixture.builds.keys.associateWith { Files.readString(fixture.report(it)) }
    fixture.validate()
    reports.forEach { (product, original) -> assertEquals(original, Files.readString(fixture.report(product))) }
  }

  @Test
  fun `Tart accepts other canonical guest workspaces and bare immutable digests`() = withFixture { fixture ->
    fixture.useTart(workspace = "/opt/isolated fixture/work", baseImageIdentity = "sha256:${"a".repeat(64)}")
    fixture.validate()
  }

  @Test
  fun `Tart receipt cannot fall back to a successful Docker state`() = withFixture { fixture ->
    fixture.useTart()
    fixture.write(fixture.run.resolve("container-final.json"),
      """[{"State":{"Status":"exited","ExitCode":0,"Running":false,"OOMKilled":false}}]""")
    fixture.builds.keys.forEach { product -> fixture.replaceReport(product, "${fixture.guestWorkspace}/", "/work/") }
    fixture.rejectReceipt("kind", "docker", "Unsupported isolated runner")
    fixture.rejectReceipt("schemaVersion", 2, "Unsupported isolated runner")
    fixture.rejectReceipt("schemaVersion", "1", "Unsupported isolated runner")
    Files.delete(fixture.run.resolve("guest-completion.json"))
    fixture.rejected()
    fixture.write(fixture.run.resolve("isolated-runner.json"), "null")
    fixture.rejected()
    Files.delete(fixture.run.resolve("isolated-runner.json"))
    Files.createSymbolicLink(fixture.run.resolve("isolated-runner.json"), Path.of("missing-receipt.json"))
    fixture.rejected()
  }

  @Test
  fun `Tart host requires both successful exits and true completion`() = withFixture { fixture ->
    fixture.useTart()
    listOf("guestCommandExitCode", "hostTransportExitCode").forEach { field ->
      listOf(1, null, "0", 0.5).forEach { value ->
        fixture.rejectReceipt(field, value, "Tart isolated run did not finish successfully")
      }
    }
    listOf(false, null, "true", 1).forEach { value ->
      fixture.rejectReceipt("completed", value, "Tart isolated run did not finish successfully")
    }
  }

  @Test
  fun `Tart host receipt must bind exact source manifest and publication bytes`() = withFixture { fixture ->
    fixture.useTart()
    listOf("sourceManifestSha256", "artifactSha256").forEach { field ->
      fixture.rejectReceipt(field, "0".repeat(64), "Tart runner source or artifact digest differs")
      fixture.rejectReceipt(field, null, "Tart runner source or artifact digest differs")
    }
    val manifest = fixture.run.resolve("source-manifest.json")
    fixture.write(manifest, Files.readString(manifest) + "\n")
    fixture.rejected("Tart runner source or artifact digest differs")
  }

  @Test
  fun `Tart guest completion must independently match all receipt fields`() = withFixture { fixture ->
    fixture.useTart()
    mapOf(
      "schemaVersion" to 2,
      "guestWorkspace" to "/Users/admin/another-run/work",
      "sourceManifestSha256" to "0".repeat(64),
      "artifactSha256" to "0".repeat(64),
      "guestCommandExitCode" to 1,
      "completed" to false,
    ).forEach { (field, invalid) ->
      listOf(invalid, null).forEach { value ->
        fixture.rejectReceipt(field, value, "Tart guest completion receipt", "guest-completion.json")
      }
    }
    fixture.rejectReceipt("guestCommandExitCode", "0", "Tart guest completion receipt", "guest-completion.json")
    fixture.rejectReceipt("completed", "true", "Tart guest completion receipt", "guest-completion.json")
  }

  @Test
  fun `Tart guest workspace must be canonical absolute and non-root`() = withFixture { fixture ->
    fixture.useTart()
    listOf("", "work", "/", "/Users/admin/work/", "/Users//admin/work", "/Users/admin/./work",
      "/Users/admin/../work", "/Users/admin\\work", "/Users/admin/work\n").forEach { path ->
      fixture.rejectReceipt("guestWorkspace", path, "Unsafe guest workspace")
    }
  }

  @Test
  fun `Tart launch must name the VM and disable all host interaction`() = withFixture { fixture ->
    fixture.useTart()
    val argv = fixture.tartLaunchArgv
    listOf("--no-graphics", "--no-audio", "--no-clipboard").forEach { flag ->
      fixture.rejectReceipt("launchArgv", argv - flag, "Tart launch argv")
      fixture.rejectReceipt("launchArgv", argv + "$flag=false", "Tart launch argv")
    }
    listOf("--vnc", "--vnc-experimental", "--capture-system-keys").forEach { flag ->
      fixture.rejectReceipt("launchArgv", argv + flag, "Tart launch argv")
      fixture.rejectReceipt("launchArgv", argv + "$flag=true", "Tart launch argv")
    }
    listOf(argv - "autocomplete-065-native", argv - "run", listOf("echo") + argv,
      argv + "autocomplete-065-native", argv + 7, argv.take(2) + "--" + argv.drop(2),
      argv.dropLast(1) + listOf("--dir", "autocomplete-065-native", "another-vm"),
      argv.take(2) + "--dir" + argv.drop(2)).forEach { invalid ->
      fixture.rejectReceipt("launchArgv", invalid, "Tart launch argv")
    }
    fixture.rejectReceipt("launchArgv", "tart run --no-graphics", "Tart launch argv")
  }

  @Test
  fun `Tart VM and immutable image identity are required`() = withFixture { fixture ->
    fixture.useTart()
    listOf(null, "", "--no-graphics", "native\nvm").forEach { value ->
      fixture.rejectReceipt("vmName", value, "Tart VM or immutable image identity")
    }
    listOf(null, "", "ghcr.io/cirruslabs/macos-tahoe-base:latest", "sha256:1234").forEach { value ->
      fixture.rejectReceipt("imageIdentity", value, "Tart VM or immutable image identity")
    }
  }

  @Test
  fun `Tart image identity must bind the exact prepared VM manifest`() = withFixture { fixture ->
    fixture.useTart()
    fixture.rejectReceipt("imageIdentity", "sha256:${"0".repeat(64)}", "prepared VM identity digest differs")
    val identity = fixture.run.resolve("prepared-vm-identity.json")
    fixture.write(identity, Files.readString(identity) + "\n")
    fixture.rejected("prepared VM identity digest differs")
    Files.delete(identity)
    fixture.rejected()
  }

  @Test
  fun `Tart prepared VM manifest requires its schema name base identity and exact file hashes`() = withFixture { fixture ->
    fixture.useTart()
    mapOf(
      "schemaVersion" to 2,
      "vmName" to "different-native-vm",
      "baseImageIdentity" to "ghcr.io/cirruslabs/macos-tahoe-base:latest",
      "files" to emptyMap<String, String>(),
    ).forEach { (field, invalid) ->
      listOf(invalid, null).forEach { value -> fixture.rejectPreparedIdentity(field, value) }
    }
    fixture.rejectPreparedIdentity("schemaVersion", "1")
    fixture.rejectPreparedIdentity("files", listOf("config.json", "disk.img", "nvram.bin"))
    val hashes = mapOf("config.json" to "a".repeat(64), "disk.img" to "b".repeat(64), "nvram.bin" to "c".repeat(64))
    hashes.keys.forEach { file ->
      fixture.rejectPreparedIdentity("files", hashes - file)
      listOf("invalid", "A".repeat(64), 1234, null).forEach { value ->
        fixture.rejectPreparedIdentity("files", hashes + (file to value))
      }
    }
    fixture.rejectPreparedIdentity("files", hashes + ("unexpected.bin" to "d".repeat(64)))
  }

  @Test
  fun `Tart guest path mapping rejects sibling workspace and session traversal`() = withFixture { fixture ->
    fixture.useTart()
    val report = fixture.report("IU")
    val original = Files.readString(report)
    fixture.replaceReport("IU", "${fixture.guestWorkspace}/out/", "${fixture.guestWorkspace}-other/out/")
    fixture.rejected("outside its guest workspace")
    fixture.write(report, original.replace("fixture-1/after-restart", "fixture-1/../fixture-1/after-restart"))
    fixture.rejected("outside its guest workspace")
  }

  @Test
  fun `Tart retains strict log and payload gates across both restart sessions`() = withFixture { fixture ->
    fixture.useTart()
    listOf("before-restart", "after-restart").forEach { phase ->
      val log = fixture.sessionHome("AI", phase).resolve("log/idea.log")
      val original = Files.readString(log)
      fixture.write(log, "ERROR - #com.intellij.platform.Platform - unrelated platform error\n")
      fixture.rejected("runtime errors")
      fixture.write(log, original)
      val payload = fixture.sessionHome("AI", phase).resolve("installed-plugin-payload.json")
      val originalPayload = Files.readString(payload)
      fixture.write(payload, "{}")
      fixture.rejected("Restart session installed payload differs")
      fixture.write(payload, originalPayload)
    }
  }

  @Test
  fun `missing installer restart proof cannot pass`() = withFixture { fixture ->
    fixture.replaceReport("IU", "Installed restart evidence:", "Restart evidence absent:")
    fixture.rejected("Installed restart evidence")
  }

  @Test
  fun `same process or reused process start time cannot count as restart`() = withFixture { fixture ->
    val report = fixture.report("IU")
    val original = Files.readString(report)
    fixture.replaceReport("IU", "\"afterProcess\":\"202@tester\"", "\"afterProcess\":\"101@tester\"")
    fixture.rejected("fresh IDE process")
    fixture.write(report, original.replace("\"afterStartTime\":2000", "\"afterStartTime\":1000"))
    fixture.rejected("fresh IDE process")
  }

  @Test
  fun `installer scheduling refusal and persisted settings assertions are mandatory`() = withFixture { fixture ->
    val report = fixture.report("IU")
    val original = Files.readString(report)
    listOf("restartRequired", "dynamicModificationRefused", "updateScheduled", "settingsPersisted").forEach { field ->
      fixture.write(report, original.replace("\"$field\":true", "\"$field\":false"))
      fixture.rejected("Missing installer/restart proof ($field)")
    }
  }

  @Test
  fun `post-restart terminal input must generate one unexecuted request`() = withFixture { fixture ->
    val report = fixture.report("IU")
    val original = Files.readString(report)
    fixture.write(report, original.replace("\"physicalTabRequests\":1", "\"physicalTabRequests\":2"))
    fixture.rejected("one physical terminal Tab request")
    fixture.write(report, original.replace("\"commandExecuted\":false", "\"commandExecuted\":true"))
    fixture.rejected("one physical terminal Tab request")
  }

  @Test
  fun `both session payload manifests must match the exact archive`() = withFixture { fixture ->
    val before = fixture.sessionHome("IU", "before-restart").resolve("installed-plugin-payload.json")
    val original = Files.readString(before)
    fixture.write(before, "{}")
    fixture.rejected("Restart session installed payload differs")
    fixture.write(before, original)
    val after = fixture.sessionHome("IU", "after-restart").resolve("installed-plugin-payload.json")
    Files.delete(after)
    fixture.rejected()
  }

  @Test
  fun `restart artifact and session directories cannot be substituted`() = withFixture { fixture ->
    val report = fixture.report("IU")
    val original = Files.readString(report)
    fixture.write(report, original.replace("\"archiveSha256\":\"${fixture.hash}\"", "\"archiveSha256\":\"${"0".repeat(64)}\""))
    fixture.rejected("Restart evidence targets a different")
    fixture.write(report, original.replace("fixture-1/after-restart", "another-fixture/after-restart"))
    fixture.rejected("Restart session does not belong")
  }

  @Test
  fun `publication descriptor must explicitly require restart`() = withFixture { fixture ->
    listOf(false, null).forEach { restartRequired ->
      fixture.zip(fixture.publication, "plugin bytes", restartRequired = restartRequired)
      fixture.rejected("plugin descriptor with require-restart=true")
    }
  }

  @Test
  fun `missing publication plugin descriptor cannot pass`() = withFixture { fixture ->
    fixture.zip(fixture.publication, "plugin bytes", includeDescriptor = false)
    fixture.rejected("plugin descriptor with require-restart=true")
  }

  @Test
  fun `archived path traversal cannot select outside evidence`() = withFixture { fixture ->
    fixture.write(fixture.evidence.resolve("release-evidence.json"),
      """{"schemaVersion":1,"runs":{"IU":"../other","PY":"run1","AI":"run1"}}""")
    fixture.rejected("Unsafe archived evidence path")
  }

  @Test
  fun `JUnit external entities are rejected without reading them`() = withFixture { fixture ->
    fixture.write(fixture.report("IU"), """
      <!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///nonexistent-secret">]>
      <testsuite name="&xxe;"/>
    """.trimIndent())
    fixture.rejected()
  }

  private fun withFixture(action: (Fixture) -> Unit) {
    val root = Files.createTempDirectory("release-evidence-test-")
    try { action(Fixture(root)) } finally { root.toFile().deleteRecursively() }
  }

  private class Fixture(root: Path) {
    val repo = root.resolve("repo")
    val evidence = root.resolve("evidence")
    val run = evidence.resolve("run1")
    val publication = root.resolve("publication.zip")
    val currentBuild = root.resolve("current-build.zip")
    val builds = mapOf("IU" to "262.10315.125", "PY" to "262.9437.214", "AI" to "261.26222.65.2614.16204760")
    val hash: String
    val guestWorkspace = "/Users/admin/autocomplete/run-20260906T090000/work"
    val tartLaunchArgv = listOf("/opt/homebrew/bin/tart", "run", "--no-graphics", "--no-audio", "--no-clipboard",
      "--dir", "run:/archive", "--dir", "cache:/gradle-cache", "--dir", "jdk:/jdk:ro", "autocomplete-065-native")
    private val tasks = mapOf("IU" to "Ide", "PY" to "PyCharm", "AI" to "AndroidStudio")

    init {
      Files.createDirectories(repo)
      check(ProcessBuilder("git", "init", "--quiet", repo.toString()).start().waitFor() == 0)
      val sources = mapOf(
        "src/main/example.kt" to "production code",
        "src/ideTest/fixture.kt" to "full fixture code",
        "build.gradle.kts" to "build configuration",
        "gradle.properties" to "maintained targets",
      )
      sources.forEach { (name, content) ->
        write(repo.resolve(name), content)
        write(run.resolve("work/$name"), content)
      }
      write(run.resolve("source-manifest.json"), Gson().toJson(sources.mapValues { (name, _) ->
        ReleaseEvidenceValidator.sha256(repo.resolve(name))
      }))
      zip(publication, "plugin bytes")
      Files.copy(publication, currentBuild)
      hash = ReleaseEvidenceValidator.sha256(publication)
      Files.createDirectories(run.resolve("artifact"))
      Files.copy(publication, run.resolve("artifact/plugin.zip"))
      write(run.resolve("artifact/SHA256SUMS"), "$hash  plugin.zip\n")
      write(run.resolve("container-final.json"),
        """[{"State":{"Status":"exited","ExitCode":0,"Running":false,"OOMKilled":false}}]""")
      write(evidence.resolve("release-evidence.json"),
        """{"schemaVersion":1,"runs":{"IU":"run1","PY":"run1","AI":"run1"}}""")
      builds.forEach { (product, build) ->
        val installed = testHome(product).resolve("plugins/plugin/lib/plugin.jar")
        Files.createDirectories(installed.parent)
        Files.write(installed, jarBytes("plugin bytes"))
        val payload = mapOf("plugin/lib/plugin.jar" to ReleaseEvidenceValidator.sha256(installed))
        listOf("before-restart", "after-restart").forEach { phase ->
          write(sessionHome(product, phase).resolve("log/idea.log"), "INFO - #com.intellij.idea.Main - IDE started and stopped\n")
          write(sessionHome(product, phase).resolve("installed-plugin-payload.json"), Gson().toJson(payload))
        }
        val restartEvidence = Gson().toJson(mapOf(
          "productCode" to product,
          "targetBuild" to "$product-$build",
          "archiveSha256" to hash,
          "beforeProcess" to "101@tester",
          "afterProcess" to "202@tester",
          "beforeStartTime" to 1000L,
          "afterStartTime" to 2000L,
          "restartRequired" to true,
          "dynamicModificationRefused" to true,
          "updateScheduled" to true,
          "settingsPersisted" to true,
          "physicalTabRequests" to 1,
          "commandExecuted" to false,
          "firstTestHome" to "/work/out/ide-tests/tests/$product-locally-installed-ide/fixture-1/before-restart",
          "secondTestHome" to "/work/out/ide-tests/tests/$product-locally-installed-ide/fixture-1/after-restart",
        ))
        write(report(product), """
          <testsuite name="com.kkoemets.subscriptionautocomplete.ide.InstalledPluginSmokeTest" tests="1" failures="0" errors="0" skipped="0">
            <testcase name="fixture ghost text requires acceptance"/>
            <system-out><![CDATA[Installed plugin artifact: /artifact/plugin.zip; bytes=123; SHA-256=$hash
          Installed compatibility target: $product-$build
          [00:00:00]: Using IDE paths: IDE Test Paths at /work/out/ide-tests/tests/$product-locally-installed-ide/fixture-1
          Installed fixture home: /work/out/ide-tests/tests/$product-locally-installed-ide/fixture-1
          classic terminal: physical Tab replaced the request; editable command remained unexecuted; explicit Enter created the sentinel
          Reworked terminal: physical Tab replaced the request; editable command remained unexecuted; explicit Enter created the sentinel
          Classic running-program: exactly one Tab byte reached the child; no provider request
          Classic alternate-program: exactly one Tab byte reached the child; no provider request
          Reworked running-program: exactly one Tab byte reached the child; no provider request
          Reworked alternate-program: exactly one Tab byte reached the child; no provider request
          Reworked search: Find handled physical Tab; terminal input unchanged; no provider request
          Fresh installation: enabled=true; manualOnly=false; automaticEngine=SELECTED_SUBSCRIPTION; no prewritten plugin settings
          Installed restart evidence: $restartEvidence
          Automatic fixture sample.kt: initial automatic suggestion accepted directly; acceptance: physical Tab
          Fixture sample.kt: ghost text displayed; explicit acceptance preserved surrounding text; physical typing: true
          ]]></system-out></testsuite>
        """.trimIndent())
      }
    }

    fun testHome(product: String): Path = run.resolve("work/out/ide-tests/tests/$product-locally-installed-ide/fixture-1")
    fun sessionHome(product: String, phase: String): Path = testHome(product).resolve(phase)
    fun report(product: String): Path = run.resolve("work/build/test-results/autocompleteInstalled${tasks.getValue(product)}Test/" +
      "TEST-com.kkoemets.subscriptionautocomplete.ide.InstalledPluginSmokeTest.xml")
    fun write(path: Path, content: String) { Files.createDirectories(path.parent); Files.writeString(path, content) }
    fun replaceReport(product: String, before: String, after: String) {
      val path = report(product)
      write(path, Files.readString(path).replace(before, after))
    }
    fun useTart(
      workspace: String = guestWorkspace,
      baseImageIdentity: String = "ghcr.io/cirruslabs/macos-tahoe-base@sha256:${"a".repeat(64)}",
    ) {
      Files.delete(run.resolve("container-final.json"))
      write(run.resolve("prepared-vm-identity.json"), Gson().toJson(mapOf(
        "schemaVersion" to 1,
        "vmName" to "autocomplete-065-native",
        "baseImageIdentity" to baseImageIdentity,
        "files" to mapOf("config.json" to "a".repeat(64), "disk.img" to "b".repeat(64), "nvram.bin" to "c".repeat(64)),
      )))
      val common = mapOf(
        "schemaVersion" to 1,
        "guestWorkspace" to workspace,
        "sourceManifestSha256" to ReleaseEvidenceValidator.sha256(run.resolve("source-manifest.json")),
        "artifactSha256" to hash,
        "guestCommandExitCode" to 0,
        "completed" to true,
      )
      write(run.resolve("guest-completion.json"), Gson().toJson(common))
      write(run.resolve("isolated-runner.json"), Gson().toJson(common + mapOf(
        "kind" to "tart",
        "hostTransportExitCode" to 0,
        "vmName" to "autocomplete-065-native",
        "imageIdentity" to "sha256:${ReleaseEvidenceValidator.sha256(run.resolve("prepared-vm-identity.json"))}",
        "launchArgv" to tartLaunchArgv,
      )))
      builds.keys.forEach { product -> replaceReport(product, "/work/", "$workspace/") }
    }
    fun rejectReceipt(field: String, value: Any?, message: String, name: String = "isolated-runner.json") {
      val path = run.resolve(name)
      val original = Files.readString(path)
      try {
        val receipt = JsonParser.parseString(original).asJsonObject
        if (value == null) receipt.remove(field) else receipt.add(field, Gson().toJsonTree(value))
        write(path, receipt.toString())
        rejected(message)
      } finally {
        write(path, original)
      }
    }
    fun rejectPreparedIdentity(field: String, value: Any?) {
      val identityPath = run.resolve("prepared-vm-identity.json")
      val runnerPath = run.resolve("isolated-runner.json")
      val originalIdentity = Files.readString(identityPath)
      val originalRunner = Files.readString(runnerPath)
      try {
        val identity = JsonParser.parseString(originalIdentity).asJsonObject
        if (value == null) identity.remove(field) else identity.add(field, Gson().toJsonTree(value))
        write(identityPath, identity.toString())
        val runner = JsonParser.parseString(originalRunner).asJsonObject
        runner.addProperty("imageIdentity", "sha256:${ReleaseEvidenceValidator.sha256(identityPath)}")
        write(runnerPath, runner.toString())
        rejected("Invalid Tart prepared VM identity")
      } finally {
        write(identityPath, originalIdentity)
        write(runnerPath, originalRunner)
      }
    }
    fun zip(
      path: Path,
      content: String,
      comment: String? = null,
      restartRequired: Boolean? = true,
      includeDescriptor: Boolean = true,
    ) {
      ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        zip.setComment(comment)
        zip.putNextEntry(ZipEntry("plugin/lib/plugin.jar").apply { time = 0 })
        zip.write(jarBytes(content, restartRequired, includeDescriptor))
        zip.closeEntry()
      }
    }
    fun jarBytes(content: String, restartRequired: Boolean? = true, includeDescriptor: Boolean = true): ByteArray {
      val bytes = ByteArrayOutputStream()
      ZipOutputStream(bytes).use { jar ->
        if (includeDescriptor) {
          jar.putNextEntry(ZipEntry("META-INF/plugin.xml").apply { time = 0 })
          val attribute = restartRequired?.let { "require-restart=\"$it\"" }.orEmpty()
          jar.write("<idea-plugin $attribute><id>com.kkoemets.subscriptionautocomplete</id></idea-plugin>".toByteArray())
          jar.closeEntry()
        }
        jar.putNextEntry(ZipEntry("plugin-payload.txt").apply { time = 0 })
        jar.write(content.toByteArray())
        jar.closeEntry()
      }
      return bytes.toByteArray()
    }
    fun validate(builds: Map<String, String> = this.builds) {
      ReleaseEvidenceValidator.validate(repo, evidence, publication, currentBuild, builds)
    }
    fun rejected(message: String? = null, builds: Map<String, String> = this.builds) {
      val failure = assertFails { validate(builds) }
      if (message != null) assertTrue(failure.message.orEmpty().contains(message), failure.toString())
    }
  }
}
