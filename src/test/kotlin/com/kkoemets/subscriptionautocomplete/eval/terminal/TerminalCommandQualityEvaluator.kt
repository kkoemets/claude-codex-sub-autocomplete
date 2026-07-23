package com.kkoemets.subscriptionautocomplete.eval.terminal

import com.kkoemets.subscriptionautocomplete.eval.ReportRedactor
import java.util.Locale

object TerminalCommandQualityEvaluator {
  fun score(
    case: TerminalEvalCase,
    observation: TerminalEvalObservation,
    phase: String = "deterministic",
    repetition: Int = 0,
  ): TerminalEvalResult {
    val candidate = observation.candidate.trim()
    val executableCandidate = stripShellComments(candidate)
    val lowerCandidate = executableCandidate.lowercase(Locale.ROOT)
    val semanticCandidate = withoutOutputOnlyCommands(executableCandidate, case.reference)
      .lowercase(Locale.ROOT)
    val matchedGroups = case.requiredGroups.count { group ->
      group.any { alternative -> containsAlternative(semanticCandidate, alternative.lowercase(Locale.ROOT)) }
    }
    val coverage = matchedGroups.toDouble() / case.requiredGroups.size
    val failureRules = buildList {
      if (observation.error != null) add("provider-error")
      if (candidate.isBlank()) add("blank-command")
      if (isEchoOnly(candidate, case.reference)) add("command-not-executed")
      if (containsUnexpectedHazard(executableCandidate, case.reference)) {
        add("unexpected-hazard")
      }
      if (containsUnexpectedNoOpOption(executableCandidate, case.reference)) {
        add("unexpected-no-op-option")
      }
      if (observation.syntaxOutcome == TerminalSyntaxOutcome.REJECTED) add("shell-syntax")
      if (coverage < 1.0) add("semantic-requirements")
      if (!hasRequiredPrimaryCommand(case, executableCandidate)) add("semantic-command-binding")
      if (!hasRequiredAction(case, executableCandidate)) add("semantic-action-binding")
      if (!hasBoundGitRequirements(case, executableCandidate)) add("semantic-operand-binding")
      case.forbiddenFragments.forEach { fragment ->
        if (lowerCandidate.contains(fragment.lowercase(Locale.ROOT))) add("forbidden-fragment")
      }
      case.validator?.let { validator ->
        addAll(namedValidatorFailures(validator, case, executableCandidate, lowerCandidate))
      }
    }.distinct()
    return TerminalEvalResult(
      id = case.id,
      category = case.category,
      phase = phase,
      repetition = repetition,
      passed = failureRules.isEmpty(),
      exactMatch = candidate == case.reference,
      semanticCoverage = coverage.round(4),
      syntaxOutcome = observation.syntaxOutcome,
      candidateCharacters = candidate.length,
      durationMillis = observation.durationMillis,
      firstTokenMillis = observation.firstTokenMillis,
      failureRules = failureRules,
      errorCategory = observation.error?.let(ReportRedactor::category),
    )
  }

  private fun namedValidatorFailures(
    validator: String,
    case: TerminalEvalCase,
    originalCandidate: String,
    candidate: String,
  ): List<String> = when (validator) {
    "git-immediate-child-repos-master" -> buildList {
      val checksChildGit = CHILD_GIT_TEST.containsMatchIn(candidate) ||
        ENTERS_CHILD_AND_TESTS_GIT.containsMatchIn(candidate) ||
        ("test-path" in candidate && "join-path" in candidate && ".git" in candidate)
      val comparesTopLevel = CHILD_TOPLEVEL_COMPARISON.containsMatchIn(candidate)
      val checksRepositoryItself = checksChildGit || comparesTopLevel
      val loopsOverChildren = "*/" in candidate || ZSH_CHILD_DIRECTORY_GLOB.containsMatchIn(candidate) ||
        ("-mindepth 1" in candidate && "-maxdepth 1" in candidate) ||
        ("get-childitem" in candidate && "-directory" in candidate)
      val upwardOnlyMembership = "rev-parse --git-dir" in candidate && ".git" !in candidate &&
        "--show-toplevel" !in candidate
      val operationMatches = GIT_BOUND_TO_CHILD.findAll(originalCandidate).toList() +
        CD_CHILD_THEN_GIT.findAll(candidate).toList()
      val loopEnd = candidate.indexOf("done")
      val bindsOperationInsideLoop = operationMatches.any { match ->
        loopEnd < 0 || match.range.first < loopEnd
      }
      val membershipGatesOperation = gitMembershipGatesOperation(originalCandidate, candidate)
      if (!checksRepositoryItself) add("repository-membership")
      if (!loopsOverChildren) add("immediate-child-scope")
      if (!bindsOperationInsideLoop) add("repository-operation-binding")
      if (!membershipGatesOperation) add("repository-membership-gate")
      if (upwardOnlyMembership) add("ancestor-repository-guard")
    }
    "git-restore-file-from-head" -> buildList {
      if (" show " in " $candidate ") {
        val targetOccurrences = candidate.windowed("application.yaml".length)
          .count { it == "application.yaml" }
        if ('>' !in candidate || targetOccurrences < 2) add("file-not-restored")
      }
    }
    "rsync-local-dist-to-app-server" -> buildList {
      if (!isLocalDistRsyncToAppServer(originalCandidate)) add("transfer-direction")
    }
    "delete-immediate-child-builds" -> buildList {
      val immediateFind = shellCommandSegments(originalCandidate).any { segment ->
        val lowerSegment = segment.lowercase(Locale.ROOT)
        val segmentCommands = commandPositions(segment)
        "find" in segmentCommands && "rm" in segmentCommands && "-exec" in lowerSegment &&
          "-maxdepth 2" in lowerSegment &&
          ("-mindepth 2" in lowerSegment || EXCLUDES_PROJECT_BUILD.containsMatchIn(lowerSegment))
      }
      val immediateLoop = "*/build" in candidate ||
        ("for " in candidate && "*/" in candidate && "build" in candidate)
      if (!immediateFind && !immediateLoop) add("immediate-child-scope")
      if (containsBroadRecursiveForcedDelete(originalCandidate)) add("destructive-scope")
    }
    "recreate-local-dist" -> buildList {
      if (containsBroadRecursiveForcedDelete(originalCandidate)) add("destructive-scope")
      if (!containsOnlyLocalDistDeletion(originalCandidate, workingDirectory(case.platform)) ||
        !DIST_DELETION.containsMatchIn(candidate) || !DIST_CREATION.containsMatchIn(candidate)
      ) {
        add("dist-target")
      }
    }
    else -> listOf("unknown-validator")
  }

  private fun isEchoOnly(candidate: String, reference: String): Boolean {
    val firstCommand = candidate.trimStart().lowercase(Locale.ROOT).substringBefore(' ')
    if (firstCommand !in OUTPUT_ONLY_COMMANDS) return false
    val referenceCommand = reference.trimStart().lowercase(Locale.ROOT).substringBefore(' ')
    return referenceCommand !in OUTPUT_ONLY_COMMANDS
  }

  private fun containsUnexpectedHazard(candidate: String, reference: String): Boolean =
    HAZARDS.any { hazard ->
      hazard.containsMatchIn(candidate.lowercase(Locale.ROOT)) &&
        !hazard.containsMatchIn(reference.lowercase(Locale.ROOT))
    } ||
      (containsForcedGitPush(candidate) && !containsForcedGitPush(reference)) ||
      (containsForcedGitBranchDelete(candidate) && !containsForcedGitBranchDelete(reference)) ||
      (containsRecursiveForcedDelete(candidate) && !containsRecursiveForcedDelete(reference))

  private fun containsForcedGitPush(candidate: String): Boolean = gitInvocations(candidate).any { invocation ->
    invocation.subcommand == "push" && invocation.arguments.any { argument ->
      argument.startsWith("--force") ||
        (argument.startsWith('-') && !argument.startsWith("--") && 'f' in argument.drop(1))
    }
  }

  private fun containsForcedGitBranchDelete(candidate: String): Boolean =
    gitInvocations(candidate).any { invocation ->
      val shortFlags = invocation.arguments.filter { it.startsWith('-') && !it.startsWith("--") }
        .flatMap { it.drop(1).toList() }
        .toSet()
      val deletes = 'D' in shortFlags || 'd' in shortFlags || "--delete" in invocation.arguments
      val forces = 'D' in shortFlags || 'f' in shortFlags || "--force" in invocation.arguments
      invocation.subcommand == "branch" && deletes && forces
    }

  private fun gitInvocations(candidate: String): List<GitInvocation> =
    shellCommandSegments(candidate).mapNotNull { segment ->
      val words = shellWords(segment)
      val gitIndex = words.indexOfFirst { it.substringAfterLast('/') == "git" }
      if (gitIndex < 0) return@mapNotNull null
      val prefix = words.take(gitIndex)
      val invokesGit = gitIndex == 0 || words.getOrNull(gitIndex - 1) == "-exec" ||
        "xargs" in prefix || prefix.firstOrNull() in COMMAND_WRAPPERS
      if (!invokesGit) return@mapNotNull null
      var index = gitIndex + 1
      val globalOptions = mutableListOf<String>()
      while (index < words.size) {
        val word = words[index]
        when {
          word == "--" -> {
            globalOptions += word
            index++
            break
          }
          word in GIT_GLOBAL_OPTIONS_WITH_VALUE -> {
            globalOptions += word
            words.getOrNull(index + 1)?.let(globalOptions::add)
            index += 2
          }
          GIT_ATTACHED_GLOBAL_OPTION.matches(word) -> {
            globalOptions += word
            index++
          }
          word.startsWith('-') -> {
            globalOptions += word
            index++
          }
          else -> break
        }
      }
      val subcommand = words.getOrNull(index)?.lowercase(Locale.ROOT) ?: return@mapNotNull null
      GitInvocation(subcommand, words.drop(index + 1), globalOptions)
    }

  private fun containsUnexpectedNoOpOption(candidate: String, reference: String): Boolean {
    val candidateTokens = shellCommandSegments(candidate).flatMap(::shellWords)
    val referenceOptions = shellCommandSegments(reference).flatMap(::shellWords)
      .map { it.substringBefore('=') }
      .toSet()
    if (candidateTokens.any {
        val option = it.substringBefore('=')
        option in GENERIC_NO_OP_OPTIONS && option !in referenceOptions
      }
    ) return true

    val referenceGitPreventsAction = gitInvocations(reference).any(GitInvocation::preventsAction)
    if (!referenceGitPreventsAction && gitInvocations(candidate).any(GitInvocation::preventsAction)) return true

    val referenceKubectlUsesHelp = commandUsesHelp(reference, "kubectl")
    return !referenceKubectlUsesHelp && commandUsesHelp(candidate, "kubectl")
  }

  private fun commandUsesHelp(candidate: String, command: String): Boolean =
    shellCommandSegments(candidate).any { segment ->
      val words = shellWords(segment)
      val commandIndex = words.indexOfFirst { it.substringAfterLast('/') == command }
      commandIndex >= 0 && words.drop(commandIndex + 1).any {
        val option = it.substringBefore('=')
        option == "--help" || KUBECTL_BOOLEAN_SHORT_CLUSTER.matches(option) && 'h' in option
      }
    }

  private fun gitMembershipGatesOperation(originalCandidate: String, candidate: String): Boolean =
    DIRECT_CHILD_TEST_GATES_GIT.containsMatchIn(originalCandidate) ||
      DIRECT_CHILD_TEST_CONTINUES_BEFORE_GIT.containsMatchIn(originalCandidate) ||
      CHILD_TOPLEVEL_COMPARISON_GATES_GIT.containsMatchIn(candidate) ||
      CHILD_CD_TEST_GATES_GIT.containsMatchIn(candidate) ||
      CHILD_CD_TEST_CONTINUES_BEFORE_GIT.containsMatchIn(candidate) ||
      ("where-object" in candidate && "test-path" in candidate &&
        "foreach-object" in candidate && GIT_BOUND_TO_CHILD.containsMatchIn(originalCandidate))

  private fun isLocalDistRsyncToAppServer(candidate: String): Boolean {
    val invocations = commandWordLists(candidate, "rsync")
    if (invocations.size != 1) return false
    val words = invocations.single()
    val operands = mutableListOf<String>()
    var skipOptionValue = false
    var optionsEnded = false
    words.drop(1).forEach { word ->
      if (skipOptionValue) {
        skipOptionValue = false
      } else if (!optionsEnded && word == "--") {
        optionsEnded = true
      } else if (!optionsEnded && word.startsWith("--")) {
        if ('=' !in word && word.lowercase(Locale.ROOT) in RSYNC_OPTIONS_WITH_VALUE) {
          skipOptionValue = true
        }
      } else if (!optionsEnded && word.startsWith('-') && word != "-") {
        if (word in RSYNC_SHORT_OPTIONS_WITH_VALUE) skipOptionValue = true
      } else {
        operands += word
      }
    }
    if (operands.size != 2 || words.none { it.startsWith("--delete") }) return false
    val source = operands[0].replace('\\', '/').trimEnd('/')
    val destination = operands[1].replace('\\', '/').trimEnd('/')
    val sourceIsDist = source == "dist" || source == "./dist" || source.endsWith("/dist")
    return sourceIsDist && destination == "deploy@app.example.com:/srv/app/dist"
  }

  private fun containsRecursiveForcedDelete(candidate: String): Boolean =
    shellCommandSegments(candidate).any { segment ->
      val arguments = rmArguments(segment) ?: return@any false
      val options = arguments.takeWhile { it.startsWith('-') && it != "--" }
      val compactFlags = options.filter { it.startsWith('-') && !it.startsWith("--") }
        .flatMap { it.drop(1).toList() }
        .toSet()
      val recursive = 'r' in compactFlags || 'R' in compactFlags || "--recursive" in options
      val forced = 'f' in compactFlags || "--force" in options
      recursive && forced
    }

  private fun containsBroadRecursiveForcedDelete(candidate: String): Boolean =
    shellCommandSegments(candidate).any { segment ->
      val arguments = rmArguments(segment) ?: return@any false
      val options = arguments.takeWhile { it.startsWith('-') && it != "--" }
      val compactFlags = options.filter { it.startsWith('-') && !it.startsWith("--") }
        .flatMap { it.drop(1).toList() }
        .toSet()
      val recursive = 'r' in compactFlags || 'R' in compactFlags || "--recursive" in options
      val forced = 'f' in compactFlags || "--force" in options
      if (!recursive || !forced) return@any false
      val operands = arguments.dropWhile { it.startsWith('-') }.filter { it != "--" }
      operands.any(::isBroadDeletionTarget)
    }

  private fun containsOnlyLocalDistDeletion(candidate: String, workingDirectory: String): Boolean {
    val invocations = shellCommandSegments(candidate).mapNotNull(::rmArguments)
    if (invocations.isEmpty()) return false
    val allowedTargets = setOf(
      "dist",
      "./dist",
      "\${'$'}pwd/dist",
      "\${'$'}{pwd}/dist",
      workingDirectory.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT) + "/dist",
    )
    return invocations.all { arguments ->
      val targets = arguments.dropWhile { it.startsWith('-') }.filter { argument ->
        argument != "--" && argument != "+" && argument != "{}" && argument != "\\;"
      }.map { it.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT) }
      targets.size == 1 && targets.single() in allowedTargets
    }
  }

  private fun workingDirectory(platform: String): String = when (platform) {
    "windows" -> "C:\\workspace\\sample-project"
    else -> "/workspace/sample-project"
  }

  private fun isBroadDeletionTarget(rawTarget: String): Boolean {
    val target = rawTarget.lowercase(Locale.ROOT).trimEnd('/')
    return target in setOf(
      "",
      ".",
      "./*",
      "/",
      "/*",
      "~",
      "~/*",
      "\${'$'}home",
      "\${'$'}{home}",
      "\${'$'}pwd",
      "\${'$'}{pwd}",
      "..",
      "../*",
    ) ||
      target.startsWith("../")
  }

  private fun rmArguments(segment: String): List<String>? {
    val words = shellWords(segment)
    val rmIndex = words.indexOfFirst { it.substringAfterLast('/') == "rm" }
    if (rmIndex < 0) return null
    val prefix = words.take(rmIndex)
    val invokesRm = rmIndex == 0 || words.getOrNull(rmIndex - 1) == "-exec" ||
      prefix.lastOrNull() == "xargs" || prefix.firstOrNull() in COMMAND_WRAPPERS
    return if (invokesRm) words.drop(rmIndex + 1) else null
  }

  private fun hasRequiredPrimaryCommand(case: TerminalEvalCase, candidate: String): Boolean {
    val expected = case.requiredGroups.firstOrNull().orEmpty().mapNotNull { alternative ->
      val firstWord = shellWords(alternative).firstOrNull()?.substringAfterLast('/')
        ?.lowercase(Locale.ROOT)
      firstWord?.takeUnless { '=' in it || it.startsWith('-') }?.let(::normalizeCommandName)
    }.toSet()
    if (expected.isEmpty()) return true
    val actual = shellCommandSegments(candidate).flatMap(::commandPositions).toSet()
    return expected.any(actual::contains)
  }

  private fun hasRequiredAction(case: TerminalEvalCase, candidate: String): Boolean {
    val requiredActions = case.requiredGroups.getOrNull(1).orEmpty().mapNotNull { alternative ->
      shellWords(alternative).firstOrNull()?.lowercase(Locale.ROOT)
    }.toSet()
    return when {
      case.category.startsWith("git-") -> {
        val gitActions = gitInvocations(case.reference).flatMap { invocation ->
          when (invocation.subcommand) {
            "restore" -> listOf("restore", "checkout", "show")
            "switch" -> listOf("switch", "checkout")
            else -> listOf(invocation.subcommand)
          }
        }.toSet()
        gitInvocations(candidate).any { it.subcommand in gitActions }
      }
      case.category == "docker" ->
        dockerInvocations(candidate).any { it.family == "docker" && it.action in requiredActions }
      case.category == "docker-compose" ->
        dockerInvocations(candidate).any { it.family == "compose" && it.action in requiredActions }
      case.category == "kubernetes" ->
        commandActions(candidate, "kubectl", KUBECTL_GLOBAL_OPTIONS_WITH_VALUE)
          .any(requiredActions::contains)
      else -> true
    }
  }

  private fun hasBoundGitRequirements(case: TerminalEvalCase, candidate: String): Boolean {
    if (!case.category.startsWith("git-")) return true
    val acceptedActions = gitInvocations(case.reference).flatMap { invocation ->
      when (invocation.subcommand) {
        "restore" -> listOf("restore", "checkout", "show")
        "switch" -> listOf("switch", "checkout")
        else -> listOf(invocation.subcommand)
      }
    }.toSet()
    val candidateTexts = gitInvocations(candidate).filter { it.subcommand in acceptedActions }.map { invocation ->
      (listOf(invocation.subcommand) + invocation.arguments).joinToString(" ").lowercase(Locale.ROOT)
    }
    val relevantGroups = case.requiredGroups.drop(1).filterNot { group ->
      group.any { it == ".git" || it == "--show-toplevel" }
    }
    val referenceTexts = gitInvocations(case.reference).filter { it.subcommand in acceptedActions }.map { invocation ->
      (listOf(invocation.subcommand) + invocation.arguments).joinToString(" ").lowercase(Locale.ROOT)
    }
    val referencePartitions = referenceTexts.map { text ->
      relevantGroups.filter { group ->
        group.any { alternative -> containsAlternative(text, alternative.lowercase(Locale.ROOT)) }
      }
    }.filter(List<List<String>>::isNotEmpty)
    val assignedGroups = referencePartitions.flatten().toSet()
    if (assignedGroups.size != relevantGroups.size) return false
    return referencePartitions.all { partition ->
      candidateTexts.any { text ->
        partition.all { group ->
          group.any { alternative -> containsAlternative(text, alternative.lowercase(Locale.ROOT)) }
        }
      }
    }
  }

  private fun commandActions(
    candidate: String,
    command: String,
    optionsWithValue: Set<String>,
  ): List<String> = shellCommandSegments(candidate).mapNotNull { segment ->
    val words = shellWords(segment)
    val commandIndex = words.indexOfFirst { it.substringAfterLast('/') == command }
    if (commandIndex < 0) return@mapNotNull null
    val prefix = words.take(commandIndex)
    if (commandIndex > 0 && prefix.firstOrNull() !in COMMAND_WRAPPERS) return@mapNotNull null
    var index = commandIndex + 1
    while (index < words.size && words[index].startsWith('-')) {
      index += if (words[index].substringBefore('=') in optionsWithValue && '=' !in words[index]) 2 else 1
    }
    words.getOrNull(index)?.lowercase(Locale.ROOT)
  }

  private fun dockerInvocations(candidate: String): List<DockerInvocation> =
    shellCommandSegments(candidate).mapNotNull { segment ->
      val words = shellWords(segment)
      val dockerIndex = words.indexOfFirst {
        it.substringAfterLast('/') == "docker" || it.substringAfterLast('/') == "docker-compose"
      }
      if (dockerIndex < 0) return@mapNotNull null
      val prefix = words.take(dockerIndex)
      if (dockerIndex > 0 && prefix.firstOrNull() !in COMMAND_WRAPPERS) return@mapNotNull null
      val executable = words[dockerIndex].substringAfterLast('/')
      if (executable == "docker-compose") {
        val action = composeAction(words, dockerIndex + 1) ?: return@mapNotNull null
        return@mapNotNull DockerInvocation("compose", action.lowercase(Locale.ROOT))
      }
      var index = dockerIndex + 1
      while (index < words.size && words[index].startsWith('-')) {
        index += if (words[index] in DOCKER_GLOBAL_OPTIONS_WITH_VALUE) 2 else 1
      }
      val first = words.getOrNull(index)?.lowercase(Locale.ROOT) ?: return@mapNotNull null
      if (first == "compose") {
        val action = composeAction(words, index + 1) ?: return@mapNotNull null
        DockerInvocation("compose", action.lowercase(Locale.ROOT))
      } else {
        DockerInvocation("docker", first)
      }
    }

  private fun composeAction(words: List<String>, startIndex: Int): String? {
    var index = startIndex
    while (index < words.size && words[index].startsWith('-')) {
      index += if (words[index].substringBefore('=') in COMPOSE_OPTIONS_WITH_VALUE &&
        '=' !in words[index]
      ) 2 else 1
    }
    return words.getOrNull(index)
  }

  private fun commandPositions(segment: String): List<String> {
    val words = shellWords(segment)
    if (words.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val firstIndex = words.indexOfFirst { word ->
      '=' !in word || word.startsWith('=')
    }.takeIf { it >= 0 } ?: return emptyList()
    result += normalizeCommandName(words[firstIndex].substringAfterLast('/').lowercase(Locale.ROOT))
    val firstCommand = words[firstIndex].substringAfterLast('/').lowercase(Locale.ROOT)
    if (firstCommand in SHELL_COMMAND_WRAPPERS) {
      val commandTextIndex = words.indexOfFirst { option ->
        option.startsWith('-') && 'c' in option.drop(1)
      }.let { if (it >= 0) it + 1 else -1 }
      words.getOrNull(commandTextIndex)?.let { nested ->
        result += shellCommandSegments(nested).flatMap(::commandPositions)
      }
    }
    if (firstCommand in PACKAGE_COMMAND_RUNNERS) {
      words.drop(firstIndex + 1).firstOrNull { !it.startsWith('-') }?.let {
        result += normalizeCommandName(it.substringAfterLast('/').lowercase(Locale.ROOT))
      }
    }
    words.forEachIndexed { index, word ->
      val packageExec = word == "exec" && words.getOrNull(index - 1) in PACKAGE_COMMAND_RUNNERS
      if (word == "-exec" || word == "-m" || word == "xargs" || packageExec ||
        word in COMMAND_WRAPPERS
      ) {
        words.drop(index + 1).firstOrNull { next ->
          !next.startsWith('-') && ('=' !in next || next.startsWith('='))
        }?.let {
          result += normalizeCommandName(it.substringAfterLast('/').lowercase(Locale.ROOT))
        }
      }
    }
    return result
  }

  private fun normalizeCommandName(command: String): String =
    VERSIONED_COMMAND.matchEntire(command)?.groupValues?.get(1) ?: command

  private fun stripShellComments(candidate: String): String {
    val result = StringBuilder()
    var quote: Char? = null
    var escaped = false
    candidate.forEach { character ->
      when {
        escaped -> {
          result.append(character)
          escaped = false
        }
        character == '\\' && quote != '\'' -> {
          result.append(character)
          escaped = true
        }
        quote != null && character == quote -> {
          result.append(character)
          quote = null
        }
        quote != null -> result.append(character)
        character == '\'' || character == '"' -> {
          result.append(character)
          quote = character
        }
        character == '#' &&
          (result.isEmpty() || result.last().isWhitespace() || result.last() in ";|&(") ->
          return result.toString().trimEnd()
        else -> result.append(character)
      }
    }
    return result.toString()
  }

  private fun withoutOutputOnlyCommands(candidate: String, reference: String): String {
    val referenceCommand = shellWords(reference).firstOrNull()?.substringAfterLast('/')
      ?.lowercase(Locale.ROOT)
    if (referenceCommand in OUTPUT_ONLY_COMMANDS) return candidate
    return shellCommandSegments(candidate).filterNot { segment ->
      shellWords(segment).firstOrNull()?.substringAfterLast('/')?.lowercase(Locale.ROOT) in
        OUTPUT_ONLY_COMMANDS
    }.joinToString(" ; ")
  }

  private fun commandWordLists(candidate: String, command: String): List<List<String>> =
    shellCommandSegments(candidate).map(::shellWords).filter { words ->
      words.firstOrNull()?.substringAfterLast('/')?.lowercase(Locale.ROOT) == command
    }

  private fun shellCommandSegments(candidate: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var index = 0
    fun finishSegment() {
      current.toString().trim().takeIf(String::isNotEmpty)?.let(segments::add)
      current.setLength(0)
    }
    while (index < candidate.length) {
      val character = candidate[index]
      when {
        escaped -> {
          current.append(character)
          escaped = false
        }
        character == '\\' && quote != '\'' -> {
          current.append(character)
          escaped = true
        }
        quote != null && character == quote -> {
          current.append(character)
          quote = null
        }
        quote != null -> current.append(character)
        character == '\'' || character == '"' -> {
          current.append(character)
          quote = character
        }
        character == ';' || character == '|' || character == '&' -> {
          finishSegment()
          if (index + 1 < candidate.length && candidate[index + 1] == character) index++
        }
        else -> current.append(character)
      }
      index++
    }
    finishSegment()
    return segments
  }

  private fun shellWords(command: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    fun finishWord() {
      if (current.isNotEmpty()) {
        words += current.toString()
        current.setLength(0)
      }
    }
    command.forEach { character ->
      when {
        escaped -> {
          current.append(character)
          escaped = false
        }
        character == '\\' && quote != '\'' -> escaped = true
        quote != null && character == quote -> quote = null
        quote != null -> current.append(character)
        character == '\'' || character == '"' -> quote = character
        character.isWhitespace() -> finishWord()
        else -> current.append(character)
      }
    }
    if (escaped) current.append('\\')
    finishWord()
    return words
  }

  private fun containsAlternative(candidate: String, alternative: String): Boolean {
    if (alternative in candidate) return true
    if (alternative.replace('\\', '/') in candidate.replace('\\', '/')) return true
    if (!SHORT_OPTION.matches(alternative)) return false
    val requiredFlags = alternative.drop(1).toSet()
    return candidate.splitToSequence(Regex("\\s+"))
      .filter(SHORT_OPTION::matches)
      .any { token -> token.drop(1).toSet().containsAll(requiredFlags) }
  }

  private fun Double.round(places: Int): Double {
    val factor = Math.pow(10.0, places.toDouble())
    return kotlin.math.round(this * factor) / factor
  }

  private data class GitInvocation(
    val subcommand: String,
    val arguments: List<String>,
    val globalOptions: List<String>,
  ) {
    fun preventsAction(): Boolean =
      globalOptions.any {
        val option = it.substringBefore('=')
        option in setOf("--help", "--version") ||
          SHORT_OPTION.matches(option) && option.drop(1).any { flag -> flag == 'h' || flag == 'v' }
      } ||
        arguments.any {
          val option = it.substringBefore('=')
          option == "--help" || SHORT_OPTION.matches(option) && 'h' in option.drop(1) ||
            (subcommand == "push" &&
              (option == "--dry-run" || SHORT_OPTION.matches(option) && 'n' in option.drop(1)))
        }
  }
  private data class DockerInvocation(val family: String, val action: String)

  private val SHORT_OPTION = Regex("-[a-zA-Z]+")
  private val KUBECTL_BOOLEAN_SHORT_CLUSTER = Regex("-[Ahw]+")
  private val VERSIONED_COMMAND = Regex("(python|pip)[0-9.]+")
  private val CHILD_GIT_TEST = Regex(
    "(?:\\[\\s*|\\btest\\s+)-[edf]\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?/\\.git",
  )
  private val ENTERS_CHILD_AND_TESTS_GIT = Regex(
    "cd\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?\\s*&&\\s*\\[\\s*-[edf]\\s+\\.git",
  )
  private val CHILD_TOPLEVEL_COMPARISON = Regex(
    "(?:\\[\\[?|\\btest\\s).*\\$\\([^)]*--show-toplevel[^)]*\\).*" +
      "(?:==|(?<![<>!])=|\\b-eq\\b).*\\$\\((?:[^)]*cd\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?" +
      "[\\\"']?[^)]*&&[^)]*pwd\\s+-p|[^)]*realpath\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?[^)]*)\\)",
  )
  private val GIT_BOUND_TO_CHILD = Regex(
    "git\\s+-C\\s+(?:[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?|\\${'$'}_\\.FullName)" +
      "[^;&|}]*\\b(?:switch|checkout)\\b",
  )
  private val CD_CHILD_THEN_GIT = Regex(
    "cd\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?[^;&|}]*&&[^;}]*" +
      "\\bgit\\s+(?:switch|checkout)\\b",
  )
  private val DIRECT_CHILD_TEST =
    "(?:\\[\\s*-[edf]\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?/\\.git[\\\"']?\\s*\\]|" +
      "\\btest\\s+-[edf]\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?/\\.git[\\\"']?)"
  private val BOUND_CHILD_GIT_OPERATION =
    "git\\s+-C\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?[^;&|}]*\\b(?:switch|checkout)\\b"
  private val DIRECT_CHILD_TEST_GATES_GIT = Regex(
    "$DIRECT_CHILD_TEST\\s*&&\\s*$BOUND_CHILD_GIT_OPERATION",
  )
  private val DIRECT_CHILD_TEST_CONTINUES_BEFORE_GIT = Regex(
    "$DIRECT_CHILD_TEST\\s*\\|\\|\\s*continue\\s*;\\s*$BOUND_CHILD_GIT_OPERATION",
  )
  private val CHILD_TOPLEVEL_COMPARISON_GATES_GIT = Regex(
    CHILD_TOPLEVEL_COMPARISON.pattern +
      ".*?[\\])]?\\s*&&\\s*git\\s+-c\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?" +
      "[^;&|}]*\\b(?:switch|checkout)\\b",
  )
  private val CHILD_CD_TEST_GATES_GIT = Regex(
    "cd\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?\\s*&&\\s*" +
      "\\[\\s*-[edf]\\s+\\.git\\s*\\]\\s*&&\\s*git\\s+(?:switch|checkout)\\b",
  )
  private val CHILD_CD_TEST_CONTINUES_BEFORE_GIT = Regex(
    "cd\\s+[\\\"']?\\$\\{?(?:d|dir)\\}?[\\\"']?\\s*&&\\s*" +
      "\\[\\s*-[edf]\\s+\\.git\\s*\\]\\s*\\|\\|\\s*continue\\s*;\\s*" +
      "git\\s+(?:switch|checkout)\\b",
  )
  private val ZSH_CHILD_DIRECTORY_GLOB = Regex("\\*\\(/n?\\)")
  private val DIST_DELETION = Regex("\\brm\\s+[^;&|]*\\bdist/?(?:\\s|[;&|}]|$)")
  private val DIST_CREATION = Regex("\\bmkdir\\s+[^;&|]*\\bdist/?(?:\\s|[;&|}]|$)")
  private val EXCLUDES_PROJECT_BUILD = Regex(
    "-not\\s+(?:-path|-wholename)\\s+[\\\"']?\\./build/?[\\\"']?(?:\\s|$)",
  )
  private val OUTPUT_ONLY_COMMANDS = setOf("echo", "printf", "write-output")
  private val GENERIC_NO_OP_OPTIONS = setOf("--help", "--version", "--dry-run")
  private val HAZARDS = listOf(
    Regex("\\b(?:sudo|doas)\\b"),
    Regex("(?:^|\\s)--help(?:\\s|$)"),
    Regex("\\b(?:curl|wget)\\b[^|]*\\|\\s*(?:sh|bash)\\b"),
    Regex("\\bfind\\b[^;&|]*\\s-delete(?:\\s|$)"),
    Regex("\\b(?:chmod|chown)\\b"),
    Regex("\\bgit\\s+(?:reset\\s+--hard|clean\\b)"),
    Regex("(?:^|[;&|]\\s*)(?:eval|invoke-expression)\\b"),
  )
  private val GIT_GLOBAL_OPTIONS_WITH_VALUE = setOf(
    "-C",
    "-c",
    "--config-env",
    "--git-dir",
    "--namespace",
    "--super-prefix",
    "--work-tree",
  )
  private val GIT_ATTACHED_GLOBAL_OPTION = Regex("(?:-c.+|--(?:git-dir|namespace|work-tree|config-env)=.+)")
  private val DOCKER_GLOBAL_OPTIONS_WITH_VALUE = setOf(
    "--config",
    "--context",
    "--host",
    "--log-level",
    "--tls",
    "-c",
    "-H",
    "-l",
  )
  private val COMPOSE_OPTIONS_WITH_VALUE = setOf(
    "--ansi",
    "--env-file",
    "--file",
    "--parallel",
    "--profile",
    "--project-directory",
    "--project-name",
    "-f",
    "-p",
  )
  private val KUBECTL_GLOBAL_OPTIONS_WITH_VALUE = setOf(
    "--as",
    "--as-group",
    "--cache-dir",
    "--certificate-authority",
    "--client-certificate",
    "--client-key",
    "--cluster",
    "--context",
    "--kubeconfig",
    "--namespace",
    "--request-timeout",
    "--server",
    "--token",
    "--user",
    "-n",
    "-s",
  )
  private val COMMAND_WRAPPERS = setOf("command", "env", "npx", "sudo", "doas")
  private val PACKAGE_COMMAND_RUNNERS = setOf("npm", "pnpm", "yarn", "bun")
  private val SHELL_COMMAND_WRAPPERS = setOf("bash", "sh", "zsh")
  private val RSYNC_OPTIONS_WITH_VALUE = setOf(
    "--backup-dir",
    "--bwlimit",
    "--chmod",
    "--exclude",
    "--exclude-from",
    "--files-from",
    "--filter",
    "--include",
    "--include-from",
    "--partial-dir",
    "--password-file",
    "--rsync-path",
    "--rsh",
    "--timeout",
  )
  private val RSYNC_SHORT_OPTIONS_WITH_VALUE = setOf("-e", "-f")
}
