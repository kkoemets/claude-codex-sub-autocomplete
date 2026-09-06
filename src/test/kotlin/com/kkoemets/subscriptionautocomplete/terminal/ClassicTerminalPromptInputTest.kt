package com.kkoemets.subscriptionautocomplete.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClassicTerminalPromptInputTest {
  @Test
  fun `pasted request is read when keyboard tracking has no input`() {
    for (prompt in listOf("bash-3.2$ ", "user@host:~$ ", "root# ", "❯ ", "(venv) user@host:~$ ", "kk@host folder % ")) {
      assertEquals("# list files", ClassicTerminalPromptInput.read("", "${prompt}# list files"))
    }
  }

  @Test
  fun `root shell prompt does not turn an ordinary command into a request`() {
    assertNull(ClassicTerminalPromptInput.read("", "# cd /tmp"))
    assertEquals("# list files", ClassicTerminalPromptInput.read("", "# # list files"))
  }

  @Test
  fun `continuation prompt and unknown prompt fail closed without tracked input`() {
    assertNull(ClassicTerminalPromptInput.read("", "> # list files"))
    assertNull(ClassicTerminalPromptInput.read("", "# list files"))
  }

  @Test
  fun `known non-request input cannot become a request through a comment`() {
    assertNull(ClassicTerminalPromptInput.read("echo # list files", "$ echo # list files"))
    assertNull(ClassicTerminalPromptInput.read("", "$ echo # list files"))
    assertNull(ClassicTerminalPromptInput.read("", "$ echo '# # list files'"))
    assertNull(ClassicTerminalPromptInput.read("", "echo # # list files"))
  }

  @Test
  fun `pasted request remains eligible after navigation and appended keyboard input`() {
    assertEquals("# list files recursively", ClassicTerminalPromptInput.read(" recursively", "bash-3.2$ # list files recursively"))
  }

  @Test
  fun `fallback does not use previous output when cursor row is empty or an idle prompt`() {
    for (row in listOf("", "bash-3.2$ ", "$ #! /bin/bash", "$ # no", "$ # list\nfiles")) {
      assertNull(ClassicTerminalPromptInput.read("", row))
    }
  }

  @Test
  fun `tracked command keeps its exact text and takes precedence over rendered row`() {
    assertEquals("  # list files  ", ClassicTerminalPromptInput.read("  # list files  ", "$ stale output"))
  }
}
