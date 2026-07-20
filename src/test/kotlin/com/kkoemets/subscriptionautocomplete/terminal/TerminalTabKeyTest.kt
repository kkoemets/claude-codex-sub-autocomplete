package com.kkoemets.subscriptionautocomplete.terminal

import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalTabKeyTest {
  private val source = Canvas()

  @Test
  fun `plain Tab press is intercepted`() {
    assertTrue(TerminalTabKey.isPlainTabPress(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', 0)))
  }

  @Test
  fun `typed released and modified Tab events remain terminal owned`() {
    assertFalse(TerminalTabKey.isPlainTabPress(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)))
    assertFalse(TerminalTabKey.isPlainTabPress(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, '\t', 0)))
    assertFalse(
      TerminalTabKey.isPlainTabPress(
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', InputEvent.SHIFT_DOWN_MASK),
      ),
    )
    assertFalse(
      TerminalTabKey.isPlainTabPress(
        key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', InputEvent.CTRL_DOWN_MASK),
      ),
    )
  }

  @Test
  fun `non Tab key is not intercepted`() {
    assertFalse(TerminalTabKey.isPlainTabPress(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', 0)))
    assertFalse(TerminalTabKey.isPlainTabPress(null))
  }

  private fun key(id: Int, code: Int, character: Char, modifiers: Int): KeyEvent =
    KeyEvent(source, id, System.currentTimeMillis(), modifiers, code, character)
}
