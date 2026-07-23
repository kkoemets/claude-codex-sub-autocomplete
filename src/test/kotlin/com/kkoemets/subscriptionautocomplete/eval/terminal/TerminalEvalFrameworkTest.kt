package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.kkoemets.subscriptionautocomplete.settings.ProviderKind
import com.kkoemets.subscriptionautocomplete.terminal.TerminalCommandSanitizer
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEvalFrameworkTest {
  @Test
  fun `loads 200 positive cases across 20 balanced categories`() {
    val dataset = TerminalEvalDatasetLoader.load()

    assertEquals(TerminalEvalDatasetLoader.VERSION, dataset.version)
    assertEquals(200, dataset.cases.size)
    assertEquals(20, dataset.cases.map(TerminalEvalCase::category).distinct().size)
    assertTrue(dataset.cases.groupBy(TerminalEvalCase::category).values.all { it.size == 10 })
  }

  @Test
  fun `official live suite is exactly 50 ordered provider neutral cases`() {
    val dataset = TerminalEvalDatasetLoader.load()
    val suite = TerminalLiveSuiteLoader.load(dataset)
    val quality = SubscriptionTerminalEval.selectCases(dataset, "quality", "")
    val full = SubscriptionTerminalEval.selectCases(dataset, "full", "")

    assertEquals(TerminalLiveSuiteLoader.ID, suite.id)
    assertEquals(50, suite.caseIds.size)
    assertEquals(45, suite.minimumPasses)
    assertEquals(46, suite.targetPasses)
    assertEquals(suite.caseIds, quality.map(TerminalEvalCase::id))
    assertEquals(200, full.size)
    assertEquals(20, quality.map(TerminalEvalCase::category).distinct().size)
    assertTrue(quality.groupingBy(TerminalEvalCase::category).eachCount().values.all { it >= 2 })
    assertTrue(quality.all { TerminalShellSyntaxChecker.check(it.shell, it.reference) == TerminalSyntaxOutcome.PASSED })
  }

  @Test
  fun `every reference passes its semantic contract and sanitizer`() {
    TerminalEvalDatasetLoader.load().cases.forEach { case ->
      val command = TerminalCommandSanitizer.sanitize(case.reference)
      val result = TerminalCommandQualityEvaluator.score(
        case,
        TerminalEvalObservation(command, 0, syntaxOutcome = TerminalSyntaxOutcome.SKIPPED),
      )

      assertEquals(case.reference, command, case.id)
      assertTrue(result.passed, "${case.id}: ${result.failureRules}")
    }
  }

  @Test
  fun `all references remain absent from provider prompts`() {
    TerminalEvalDatasetLoader.load().cases.forEach { case ->
      DeterministicTerminalEval.requireOracleAbsent(case, DeterministicTerminalEval.prompt(case).combined())
    }
  }

  @Test
  fun `semantic alternatives pass without exact command equality`() {
    val case = TerminalEvalDatasetLoader.load().cases.single { it.id == "git-switch-master" }
    val candidate = "git checkout master"

    val result = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(candidate, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )

    assertTrue(result.passed)
    assertFalse(result.exactMatch)
  }

  @Test
  fun `common shell equivalents are not false negatives`() {
    val cases = TerminalEvalDatasetLoader.load().cases.associateBy(TerminalEvalCase::id)
    val equivalents = listOf(
      "files-list-child-directories" to "ls -d */",
      "files-disk-usage-items" to "du -sh /workspace/sample-project/*",
      "files-backup-config" to "tar -czf config-backup.tar.gz config",
      "search-package-json-no-node-modules" to
        "find . -name package.json -not -path '*/node_modules/*'",
      "text-remove-blank-lines" to "grep -v '^[[:space:]]*$' input.txt > output.txt",
      "kubectl-api-pod-logs" to
        "kubectl logs \$(kubectl get pods -l app=api -o name | head -1) --tail=100",
      "kubectl-shell-api-pod" to
        "kubectl exec -it \$(kubectl get pods -l app=api -o name | head -1) -- bash",
      "kubectl-port-forward-api" to "kubectl port-forward svc/api 8080:80",
      "database-mongodb-list" to "mongo --eval 'show databases'",
      "system-port-8080-mac" to "lsof -i :8080",
      "system-listening-tcp-linux" to "ss -tlnp",
      "system-top-memory" to "ps aux | sort -k4 -rn | head -11",
      "system-top-cpu" to "ps -Ao pid,ppid,comm,%cpu -r | head -n 11",
      "shell-path-lines" to "echo \$PATH | tr ':' '\\n'",
      "gradle-guava-insight" to "./gradlew dependencies | grep -i guava",
      "powershell-create-log" to
        "New-Item -ItemType Directory -Path logs\\archive -Force; " +
          "New-Item -ItemType File -Path logs\\app.log -Force",
      "archive-gzip-log" to "gzip -k application.log",
      "mutate-delete-child-builds" to
        "for dir in */; do [ -d \"\$dir/build\" ] && rm -rf \"\$dir/build\"; done",
      "mutate-delete-child-builds" to
        "find . -maxdepth 2 -type d -name build -not -path './build' -exec rm -rf {} +",
      "text-set-yaml-image-tag" to
        "yq eval '.image.tag = \"1.5.0\"' -i deployment.yaml",
      "database-redis-ping" to "bash -lc 'redis-cli -u \"\$REDIS_URL\" ping'",
      "typescript-no-emit" to "pnpm exec tsc --noEmit",
      "typescript-no-emit" to "pnpm tsc --noEmit",
      "compose-run-migrate" to "docker compose -p sample run --rm migrate",
      "mutate-recreate-dist" to
        "[ -d /workspace/sample-project/dist ] && rm -rf /workspace/sample-project/dist; " +
          "mkdir -p /workspace/sample-project/dist",
      "git-restore-application-yaml" to
        "git show HEAD:application.yaml > application.yaml",
      "git-switch-master-child-repos" to
        "for d in /workspace/sample-project/*(/); do [ -e \"\$d/.git\" ] && git -C \"\$d\" checkout master; done",
    )

    equivalents.forEach { (id, candidate) ->
      val result = TerminalCommandQualityEvaluator.score(
        cases.getValue(id),
        TerminalEvalObservation(candidate, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
      )

      assertTrue(result.passed, "$id rejected a valid alternative: ${result.failureRules}")
      assertFalse(result.exactMatch, "$id fixture must exercise a non-reference alternative")
    }
  }

  @Test
  fun `multi repo regression rejects ancestor discovering git guard`() {
    val case = TerminalEvalDatasetLoader.load().cases.single { it.id == "git-switch-master-child-repos" }
    val candidate =
      "find . -mindepth 1 -maxdepth 1 -type d -exec sh -c 'cd \"\$1\" && " +
        "git rev-parse --git-dir >/dev/null && git checkout master' _ {} \\;"

    val result = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(candidate, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )

    assertFalse(result.passed)
    assertTrue("forbidden-fragment" in result.failureRules)
    assertTrue("repository-membership" in result.failureRules)
  }

  @Test
  fun `semantic scorer rejects quoted commands reversed transfers and hazardous suffixes`() {
    val cases = TerminalEvalDatasetLoader.load().cases.associateBy(TerminalEvalCase::id)
    val rejected = listOf(
      "git-switch-master" to "echo 'git switch master'",
      "network-rsync-dist" to
        "rsync -avz --delete deploy@app.example.com:/srv/app/dist/ dist/",
      "git-status-short" to "git status --short; rm -rf build",
      "git-status-short" to "git status --short; find / -delete",
      "git-status-short" to "git status --short; chmod -R 777 /",
      "git-push-upstream" to "git help push -u origin HEAD",
      "git-push-upstream" to "git push --force -u origin HEAD",
      "git-push-upstream" to
        "git -c advice.detachedHead=false push --force -u origin HEAD",
      "git-push-upstream" to "git -C . push --force -u origin HEAD",
      "git-delete-merged-branches" to "git branch --merged main | xargs git branch -D",
      "network-rsync-dist" to
        "rsync -avz --delete --exclude dist / deploy@app.example.com:/srv/app/dist/",
      "network-rsync-dist" to
        "rsync -avz dist/ deploy@app.example.com:/srv/app/dist/; " +
          "rsync -avz --delete / deploy@app.example.com:/srv/app/dist/",
      "mutate-recreate-dist" to "rm -rf / && mkdir -p dist",
      "mutate-recreate-dist" to "rm --recursive --force / dist && mkdir -p dist",
      "mutate-recreate-dist" to "rm -rf \"/\" dist && mkdir -p dist",
      "mutate-recreate-dist" to "command rm --recursive --force / dist && mkdir -p dist",
      "mutate-recreate-dist" to "command rm -rf / dist && mkdir -p dist",
      "mutate-recreate-dist" to "env rm --recursive --force / dist && mkdir -p dist",
      "mutate-recreate-dist" to
        "target=/; rm -rf \"\$target\" dist && mkdir -p dist",
      "mutate-delete-child-builds" to
        "find . -mindepth 2 -maxdepth 2 -type d -name build -exec rm -rf / \\;",
      "mutate-delete-child-builds" to
        "find . -maxdepth 2 -type d -name build -exec rm -rf {} +; " +
          "echo -not -path ./build",
      "git-switch-master-child-repos" to
        "for d in */; do [ -e \"\$d/.git\" ] || true && git -C \"\$d\" switch master; done",
      "git-push-upstream" to "true # git push -u origin HEAD",
      "git-push-upstream" to "true && echo 'git push -u origin HEAD'",
      "git-push-upstream" to "true && echo 'x; git push -u origin HEAD'",
      "git-push-upstream" to "true git push -u origin HEAD",
      "git-push-upstream" to ": git push -u origin HEAD",
      "git-push-upstream" to "git push; true -u origin HEAD",
      "git-delete-merged-branches" to
        "git branch --merged main | xargs -r git branch -d -f",
      "git-switch-master-child-repos" to
        "for d in */; do [ -e \"\$d/.git\" ] && " +
          "git -C \"\$d\" switch --help master; done",
      "git-switch-master-child-repos" to
        "for d in */; do [ -e \"\$d/.git\" ] && " +
          "git -C \"\$d\" --version switch master; done",
      "git-switch-master-child-repos" to
        "for d in */; do [ -e \"\$d/.git\" ] && " +
          "git -C \"\$d\" switch -h master; done",
      "git-switch-master-child-repos" to
        "for d in */; do [ -e \"\$d/.git\" ] && " +
          "git -C \"\$d\" -v switch master; done",
      "git-switch-master-child-repos" to
        "for d in */; do [ -e \"\$d/.git\" ] && " +
          "git -C \"\$d\" switch -hq master; done",
      "git-push-upstream" to "git push --dry-run -u origin HEAD",
      "git-push-upstream" to "git push -n -u origin HEAD",
      "git-push-upstream" to "git push -nu origin HEAD",
      "git-push-upstream" to "git push -u; git rev-parse origin HEAD",
      "git-push-upstream" to "git push -u; git push origin HEAD",
      "kubectl-get-pods" to "kubectl help get pods",
      "kubectl-get-pods" to "kubectl get -h pods",
      "kubectl-get-pods" to "kubectl get pods --help=true",
      "kubectl-get-pods" to "kubectl get pods -h=true",
      "kubectl-get-pods" to "kubectl get pods -hA",
      "kubectl-apply-manifests" to "kubectl apply --dry-run=client -f k8s/",
      "git-status-short" to "git status --short; chown -R root /",
      "git-status-short" to "git status --short; chown -R root ..",
      "git-status-short" to "git status --short; chmod -R 777 .",
      "docker-run-api" to "docker version run -p 8080:8080 api:local",
    )

    rejected.forEach { (id, candidate) ->
      val result = TerminalCommandQualityEvaluator.score(
        cases.getValue(id),
        TerminalEvalObservation(candidate, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
      )
      assertFalse(result.passed, "$id accepted adversarial candidate: $candidate")
    }
  }

  @Test
  fun `multi repo show toplevel must be compared with each child root`() {
    val case = TerminalEvalDatasetLoader.load().cases.single { it.id == "git-switch-master-child-repos" }
    val unpaired =
      "for d in */; do git -C \"\$d\" rev-parse --show-toplevel >/dev/null && " +
        "git -C \"\$d\" switch master; done"
    val compared =
      "for d in */; do [ \"\$(git -C \"\$d\" rev-parse --show-toplevel 2>/dev/null)\" = " +
        "\"\$(cd \"\$d\" && pwd -P)\" ] && git -C \"\$d\" switch master; done"
    val wrongRoot =
      "for d in */; do [ \"\$(git -C \"\$d\" rev-parse --show-toplevel 2>/dev/null)\" = " +
        "\"\$(pwd -P)\" ] && git -C \"\$d\" switch master; done"
    val inverseComparison =
      "for d in */; do [ \"\$(git -C \"\$d\" rev-parse --show-toplevel 2>/dev/null)\" != " +
        "\"\$(cd \"\$d\" && pwd -P)\" ] && git -C \"\$d\" switch master; done"

    val rejected = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(unpaired, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )
    val accepted = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(compared, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )
    val wrongRootResult = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(wrongRoot, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )
    val inverseResult = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(inverseComparison, 1, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )

    assertFalse(rejected.passed)
    assertTrue("repository-membership" in rejected.failureRules)
    assertFalse(wrongRootResult.passed)
    assertTrue("repository-membership" in wrongRootResult.failureRules)
    assertFalse(inverseResult.passed)
    assertTrue("repository-membership" in inverseResult.failureRules)
    assertTrue(accepted.passed, accepted.failureRules.toString())
  }

  @Test
  fun `git show without writing the target does not count as restoration`() {
    val case = TerminalEvalDatasetLoader.load().cases.single { it.id == "git-restore-application-yaml" }
    val result = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(
        "git show HEAD:application.yaml",
        1,
        syntaxOutcome = TerminalSyntaxOutcome.PASSED,
      ),
    )

    assertFalse(result.passed)
    assertTrue("file-not-restored" in result.failureRules)
  }

  @Test
  fun `sample and critical profiles are deterministic and balanced`() {
    val dataset = TerminalEvalDatasetLoader.load()

    val sample = SubscriptionTerminalEval.selectCases(dataset, "sample", "")
    val critical = SubscriptionTerminalEval.selectCases(dataset, "critical", "")

    assertEquals(20, sample.size)
    assertEquals(20, sample.map(TerminalEvalCase::category).distinct().size)
    assertEquals(20, critical.size)
    assertTrue(critical.any { it.id == "git-switch-master-child-repos" })
  }

  @Test
  fun `terminal settings use production output budget and effective Claude effort`() {
    val settings = SubscriptionTerminalEval.settings(ProviderKind.CLAUDE) { key, default ->
      when (key) {
        "terminal.eval.claudeModel" -> "sonnet"
        "terminal.eval.timeoutSeconds" -> "45"
        else -> default
      }
    }

    assertEquals("sonnet", settings.claudeModel)
    assertEquals(128, settings.maxOutputTokens)
    assertEquals(45, settings.timeoutSeconds)
    assertEquals("low", SubscriptionTerminalEval.selectedEffort(ProviderKind.CLAUDE, settings))
  }

  @Test
  fun `terminal reports exclude prompts references and candidates`() {
    val case = TerminalEvalDatasetLoader.load().cases.first()
    val candidate = case.reference
    val result = TerminalCommandQualityEvaluator.score(
      case,
      TerminalEvalObservation(candidate, 12, syntaxOutcome = TerminalSyntaxOutcome.PASSED),
    )
    val report = TerminalEvalRunReport(
      metadata = TerminalEvalReportWriter.metadata(
        datasetVersion = TerminalEvalDatasetLoader.VERSION,
        provider = "fixture",
        model = "fixture-model",
        reasoningEffort = "none",
        profile = "test",
        randomSeed = 7,
        warmRepetitions = 0,
        advisory = false,
        now = Instant.parse("2026-07-22T10:00:00Z"),
      ),
      completedAt = "2026-07-22T10:00:01Z",
      results = listOf(result),
    )
    val directory = Files.createTempDirectory("terminal-eval-report")

    val paths = TerminalEvalReportWriter.write(directory, report)
    val content = listOf(paths.json, paths.jsonLines, paths.markdown, paths.junitXml)
      .joinToString("\n") { Files.readString(it) }

    assertTrue(content.contains(case.id))
    assertFalse(content.contains(case.request))
    assertFalse(content.contains(case.reference))
    assertFalse(content.contains(candidate))
  }
}
