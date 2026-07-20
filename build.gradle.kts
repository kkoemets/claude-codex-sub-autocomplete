import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("org.jetbrains.intellij.platform")
}

val ideTestSourceSet = sourceSets.create("ideTest") {
  compileClasspath += sourceSets.main.get().output
  runtimeClasspath += sourceSets.main.get().output
}

val ideTestImplementationConfiguration = configurations.getByName("ideTestImplementation") {
  extendsFrom(configurations.testImplementation.get())
}

val evalRuntimeConfiguration = configurations.create("evalRuntime")

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

dependencies {
  intellijPlatform {
    val localIdePath = providers.gradleProperty("localIdePath").orNull
    if (localIdePath.isNullOrBlank()) {
      intellijIdea(providers.gradleProperty("platformVersion").get())
    } else {
      local(localIdePath)
    }
    testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    testFramework(TestFrameworkType.Starter, configurationName = "ideTestImplementation")
  }

  implementation("com.google.code.gson:gson:2.14.0")

  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
  add(evalRuntimeConfiguration.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
  add(ideTestImplementationConfiguration.name, "org.junit.jupiter:junit-jupiter:5.7.1")
  add(ideTestImplementationConfiguration.name, "org.kodein.di:kodein-di-jvm:7.20.2")
  add(ideTestImplementationConfiguration.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.1")
  add("ideTestRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.13.4")
}

intellijPlatform {
  pluginConfiguration {
    id = providers.gradleProperty("pluginGroup").get()
    name = providers.gradleProperty("pluginName").get()
    version = providers.gradleProperty("pluginVersion").get()
    ideaVersion {
      sinceBuild = "261"
      untilBuild = provider { null }
    }
  }

  publishing {
    token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    channels.set(listOf("default"))
    hidden.set(false)
  }

  signing {
    certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
    privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
    password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
  }

  pluginVerification {
    ides {
      recommended()
    }
  }
}

kotlin {
  jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
  compilerOptions {
    jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
  }
}

tasks.test {
  useJUnit()
  systemProperty("idea.load.plugins.id", providers.gradleProperty("pluginGroup").get())
}

intellijPlatformTesting.testIdeUi.register("autocompleteInstalledIdeTest") {
  task {
    testClassesDirs = ideTestSourceSet.output.classesDirs
    classpath = ideTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty(
      "ideTest.repetitions",
      providers.gradleProperty("ideTestRepetitions").orElse("3").get(),
    )
    systemProperty(
      "ideTest.requirePhysicalTyping",
      providers.gradleProperty("requirePhysicalTyping").orElse("false").get(),
    )
  }
}

tasks.register<JavaExec>("subscriptionEvals") {
  group = "verification"
  description = "Runs live autocomplete quality and latency evals using the configured subscriptions"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath + evalRuntimeConfiguration
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.SubscriptionCompletionEval")
  args(providers.gradleProperty("evalProviders").orElse("codex,claude").get())
  systemProperty(
    "eval.outputDir",
    layout.buildDirectory.dir("reports/subscription-evals").get().asFile.absolutePath,
  )
  systemProperty("eval.repetitions", providers.gradleProperty("evalRepetitions").orElse("5").get())
  systemProperty("eval.profile", providers.gradleProperty("evalProfile").orElse("smoke").get())
  systemProperty("eval.seed", providers.gradleProperty("evalSeed").orElse("20260718").get())
  systemProperty("eval.cases", providers.gradleProperty("evalCases").orElse("").get())
  systemProperty(
    "eval.claudeModel",
    providers.gradleProperty("evalClaudeModel").orElse("haiku").get(),
  )
  systemProperty(
    "eval.codexModel",
    providers.gradleProperty("evalCodexModel").orElse("gpt-5.3-codex-spark").get(),
  )
  systemProperty(
    "eval.codexReasoningEffort",
    providers.gradleProperty("evalCodexReasoningEffort").orElse("low").get(),
  )
  systemProperty(
    "eval.contextTokenBudget",
    providers.gradleProperty("evalContextTokenBudget").orElse("1400").get(),
  )
  systemProperty(
    "eval.maxOutputTokens",
    providers.gradleProperty("evalMaxOutputTokens").orElse("512").get(),
  )
  systemProperty(
    "eval.timeoutSeconds",
    providers.gradleProperty("evalTimeoutSeconds").orElse("30").get(),
  )
}

tasks.register<JavaExec>("autocompleteDeterministicEval") {
  group = "verification"
  description = "Runs the required offline 99-case autocomplete evaluation"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.DeterministicAutocompleteEval")
  systemProperty(
    "eval.outputDir",
    layout.buildDirectory.dir("reports/autocomplete-deterministic").get().asFile.absolutePath,
  )
}

tasks.register<JavaExec>("subscriptionSampleEval") {
  group = "verification"
  description = "Runs an advisory one-request-per-surface Claude/Codex sample"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath + evalRuntimeConfiguration
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.SubscriptionCompletionEval")
  args(providers.gradleProperty("evalProviders").orElse("codex,claude").get())
  systemProperty(
    "eval.outputDir",
    layout.buildDirectory.dir("reports/subscription-sample-evals").get().asFile.absolutePath,
  )
  systemProperty("eval.profile", "smoke")
  systemProperty("eval.repetitions", "0")
  systemProperty("eval.seed", providers.gradleProperty("evalSeed").orElse("20260718").get())
  systemProperty(
    "eval.claudeModel",
    providers.gradleProperty("evalClaudeModel").orElse("haiku").get(),
  )
  systemProperty(
    "eval.codexModel",
    providers.gradleProperty("evalCodexModel").orElse("gpt-5.3-codex-spark").get(),
  )
  systemProperty(
    "eval.codexReasoningEffort",
    providers.gradleProperty("evalCodexReasoningEffort").orElse("low").get(),
  )
  systemProperty(
    "eval.contextTokenBudget",
    providers.gradleProperty("evalContextTokenBudget").orElse("1400").get(),
  )
  systemProperty(
    "eval.maxOutputTokens",
    providers.gradleProperty("evalMaxOutputTokens").orElse("512").get(),
  )
  systemProperty(
    "eval.timeoutSeconds",
    providers.gradleProperty("evalTimeoutSeconds").orElse("30").get(),
  )
}

tasks.register<JavaExec>("compareAutocompleteEval") {
  group = "verification"
  description = "Compares compatible redacted evaluation JSON reports"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.CompareAutocompleteEval")
  systemProperty("eval.baseline", providers.gradleProperty("evalBaseline").orElse("").get())
  systemProperty("eval.candidate", providers.gradleProperty("evalCandidate").orElse("").get())
}

tasks.register<JavaExec>("codexLifecycleSmoke") {
  group = "verification"
  description = "Runs live Codex completions and verifies child-process and cancellation cleanup"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath + evalRuntimeConfiguration
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.CodexLifecycleSmoke")
  systemProperty(
    "eval.codexModel",
    providers.gradleProperty("evalCodexModel").orElse("gpt-5.3-codex-spark").get(),
  )
  systemProperty(
    "eval.codexReasoningEffort",
    providers.gradleProperty("evalCodexReasoningEffort").orElse("low").get(),
  )
}

tasks.register<JavaExec>("claudeLifecycleSmoke") {
  group = "verification"
  description = "Runs a live Claude completion and verifies subprocess cancellation cleanup"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath + evalRuntimeConfiguration
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.ClaudeLifecycleSmoke")
}

tasks.register<JavaExec>("autocompleteExpectationsEval") {
  group = "verification"
  description = "Evaluates deterministic insertion boundaries and automatic trigger behavior"
  dependsOn(tasks.testClasses)
  classpath = sourceSets.test.get().runtimeClasspath
  mainClass.set("com.kkoemets.subscriptionautocomplete.eval.AutocompleteExpectationsEval")
  systemProperty(
    "eval.outputDir",
    layout.buildDirectory.dir("reports/autocomplete-expectations").get().asFile.absolutePath,
  )
}

tasks.register("verifyPluginBundleSize") {
  group = "verification"
  description = "Builds the plugin and rejects artifacts above the internal 380 MB bundle ceiling"
  dependsOn(tasks.named("buildPlugin"))
  doLast {
    val distributionDirectory = layout.buildDirectory.dir("distributions").get().asFile
    val artifact = distributionDirectory.resolve("${project.name}-${project.version}.zip")
    check(artifact.isFile) {
      "Expected current plugin ZIP at $artifact"
    }
    val maximumBytes = 380_000_000L
    check(artifact.length() <= maximumBytes) {
      "Plugin ZIP is ${artifact.length()} bytes; internal bundle ceiling is $maximumBytes bytes"
    }
    logger.lifecycle("Plugin bundle: ${artifact.length()} / $maximumBytes bytes (${artifact.name})")
  }
}

tasks.register("verifyMarketplaceMetadata") {
  group = "verification"
  description = "Validates Marketplace-facing name, metadata, legal files, and plugin logos"
  val pluginXml = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml")
  val lightIcon = layout.projectDirectory.file("src/main/resources/META-INF/pluginIcon.svg")
  val darkIcon = layout.projectDirectory.file("src/main/resources/META-INF/pluginIcon_dark.svg")
  val publicDocuments = listOf(
    "README.md",
    "CHANGELOG.md",
    "PRIVACY.md",
    "EULA.md",
    "LICENSE",
    "CONTRIBUTING.md",
    "SECURITY.md",
    "SUPPORT.md",
    "RELEASING.md",
  )
    .map(layout.projectDirectory::file)
  inputs.files(listOf(pluginXml, lightIcon, darkIcon) + publicDocuments)
  doLast {
    val marketplaceName = providers.gradleProperty("pluginName").get()
    val metadata = pluginXml.asFile.readText()
    check(marketplaceName == "Claude/Codex Sub Autocomplete") {
      "Unexpected Marketplace name: $marketplaceName"
    }
    check(marketplaceName.length <= 30) {
      "Marketplace name is ${marketplaceName.length} characters; maximum is 30"
    }
    check("<name>$marketplaceName</name>" in metadata)
    check("<vendor url=\"https://github.com/kkoemets/claude-codex-sub-autocomplete\"" in metadata)
    check("<description>" in metadata)
    check("<change-notes>" in metadata)
    check("<depends>com.intellij.modules.idea</depends>" in metadata) {
      "Marketplace compatibility must be limited to IntelliJ IDEA"
    }
    listOf(lightIcon, darkIcon).forEach { icon ->
      val svg = icon.asFile.readText()
      check("width=\"40\"" in svg && "height=\"40\"" in svg) {
        "${icon.asFile.name} must declare a 40 by 40 pixel base size"
      }
    }
    publicDocuments.forEach { document ->
      check(document.asFile.isFile && document.asFile.length() > 0) {
        "Required public document is missing or empty: ${document.asFile.name}"
      }
    }
    logger.lifecycle("Marketplace metadata ready for $marketplaceName")
  }
}

tasks.register("autocompleteReleaseGate") {
  group = "verification"
  description = "Runs unit, deterministic, installed-IDE, plugin, verifier, and bundle gates"
  dependsOn(
    tasks.test,
    tasks.named("autocompleteDeterministicEval"),
    tasks.named("autocompleteInstalledIdeTest"),
    tasks.named("buildPlugin"),
    tasks.named("verifyPlugin"),
    tasks.named("verifyPluginBundleSize"),
    tasks.named("verifyMarketplaceMetadata"),
  )
}

tasks.named("signPlugin") {
  doFirst {
    listOf("CERTIFICATE_CHAIN", "PRIVATE_KEY", "PRIVATE_KEY_PASSWORD").forEach { variable ->
      check(!System.getenv(variable).isNullOrBlank()) {
        "$variable is required for a signed Marketplace artifact"
      }
    }
  }
}

tasks.named("publishPlugin") {
  doFirst {
    check(!System.getenv("PUBLISH_TOKEN").isNullOrBlank()) {
      "PUBLISH_TOKEN is required to publish to JetBrains Marketplace"
    }
  }
}

tasks.register("marketplaceSignedReleaseGate") {
  group = "verification"
  description = "Runs the full release gate, signs the plugin, and verifies its signature"
  dependsOn(
    tasks.named("autocompleteReleaseGate"),
    tasks.named("signPlugin"),
    tasks.named("verifyPluginSignature"),
  )
}
