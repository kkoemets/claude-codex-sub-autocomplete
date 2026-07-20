package com.kkoemets.subscriptionautocomplete.terminal

import com.kkoemets.subscriptionautocomplete.completion.CompletionOutputEnvelope
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalCommandSupportTest {
  @Test
  fun `trigger accepts only a bounded one-line request at command start`() {
    assertEquals("list TypeScript files", TerminalCommandTrigger.extract("# list TypeScript files"))
    assertEquals("show git status", TerminalCommandTrigger.extract("   #show git status"))
    assertNull(TerminalCommandTrigger.extract("echo value # explain this"))
    assertNull(TerminalCommandTrigger.extract("#!/bin/zsh"))
    assertNull(TerminalCommandTrigger.extract("# x"))
    assertNull(TerminalCommandTrigger.extract("# first\nsecond"))
    assertNull(TerminalCommandTrigger.extract("# list\u001bfiles"))
  }

  @Test
  fun `trigger extracts a hash request after a rendered shell prompt`() {
    assertEquals(
      "list python files",
      TerminalCommandTrigger.extractFromRenderedLine(
        "kristjankoemets@Kristjans-MacBook-Pro gpfy % # list python files",
      ),
    )
    assertEquals(
      "show git status",
      TerminalCommandTrigger.extractFromRenderedLine("[main #3] project $ # show git status"),
    )
    assertEquals(
      "find # TODO comments",
      TerminalCommandTrigger.extractFromRenderedLine("project % # find # TODO comments"),
    )
    assertEquals(
      "list files",
      TerminalCommandTrigger.extractFromRenderedLine("root@container /app # # list files"),
    )
    assertNull(TerminalCommandTrigger.extractFromRenderedLine("project-with-#tag % echo value"))
    assertNull(TerminalCommandTrigger.extractFromRenderedLine("root #!/bin/zsh"))
  }

  @Test
  fun `rendered terminal text selects the current nonblank command line`() {
    assertEquals(
      "kristjankoemets@mac project % # list python files",
      TerminalRenderedText.currentLine(
        "previous output\nkristjankoemets@mac project % # list python files\n\n",
      ),
    )
    assertNull(TerminalRenderedText.currentLine("\n\r\n"))
    assertNull(TerminalRenderedText.currentLine("old\n\n", maximumLinesToScan = 0))
  }

  @Test
  fun `sanitizer accepts a command and a single surrounding shell fence`() {
    assertEquals("git status --short", TerminalCommandSanitizer.sanitize("git status --short"))
    assertEquals(
      "find . -name '*.ts' -print",
      TerminalCommandSanitizer.sanitize("```bash\nfind . -name '*.ts' -print\n```"),
    )
    assertEquals(
      "git status && git diff --stat",
      TerminalCommandSanitizer.sanitize("git status && git diff --stat"),
    )
    assertEquals("command -v node", TerminalCommandSanitizer.sanitize("command -v node"))
  }

  @Test
  fun `sanitizer rejects prose prompts multiline and terminal control data`() {
    listOf(
      "Here is the command: git status",
      "Command: git status",
      "The YAML structure is already complete as written.",
      "No additional command is needed.",
      "$ git status",
      "# list files",
      "git status\npwd",
      "git status\u001b[31m",
      "git status\u0000",
      "```bash\ngit status\n```\nThis lists changes.",
    ).forEach { unsafe ->
      assertEquals("", TerminalCommandSanitizer.sanitize(unsafe), "Expected rejection for $unsafe")
    }
  }

  @Test
  fun `context uses marker names without reading project files or terminal history`() {
    val project = createTempDirectory("terminal-context-")
    project.resolve(".git").createDirectory()
    project.resolve(".git/config").writeText("url=https://user:secret-token@example.invalid/repo.git")
    project.resolve("package.json").createFile()
    project.resolve("Dockerfile").createFile()
    val subdirectory = project.resolve("packages").createDirectory()

    val context = TerminalProjectContextCollector.collect(
      description = "list changed JavaScript files",
      shellCommand = listOf("/bin/zsh", "-l"),
      currentDirectory = subdirectory.toString(),
      projectName = "sample-project",
      projectBasePath = project.toString(),
    )
    val prompt = TerminalCommandPromptBuilder.build(context).combined()

    assertEquals("zsh", context.shell)
    assertTrue("git" in context.projectMarkers)
    assertTrue("node" in context.projectMarkers)
    assertTrue("docker" in context.projectMarkers)
    assertFalse(prompt.contains("secret-token"))
    assertFalse(prompt.contains("terminal history", ignoreCase = true))
  }

  @Test
  fun `prompt redacts credential-like text and requires insertion only`() {
    val prompt = TerminalCommandPromptBuilder.build(
      TerminalPromptContext(
        description = "curl with Authorization: Bearer secret-value",
        shell = "zsh",
        workingDirectory = "/tmp/project",
        projectName = "sample",
        projectMarkers = listOf("git"),
      ),
    ).combined()

    assertFalse(prompt.contains("secret-value"))
    assertTrue(prompt.contains("Return only one complete shell command"))
    assertTrue(prompt.contains("Do not execute the command"))
    assertTrue(prompt.contains("Never return a newline"))
  }

  @Test
  fun `sanitizer enforces the shared output envelope`() {
    val oversized = "x".repeat(CompletionOutputEnvelope.maxCharacters(16) + 1)

    assertEquals("", TerminalCommandSanitizer.sanitize(oversized, 16))
  }
}
