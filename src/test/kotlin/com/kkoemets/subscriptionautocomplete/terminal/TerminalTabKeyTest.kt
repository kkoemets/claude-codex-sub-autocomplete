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

  @Test
  fun `Tab already consumed by the IDE remains handled once`() {
    val event = key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', 0)
    event.consume()
    assertFalse(TerminalTabKey.isPlainTabPress(event))
  }

  @Test
  fun `terminal search controls are outside terminal input focus`() {
    val panel = java.awt.Panel()
    val input = java.awt.Panel()
    val editorChild = Canvas()
    val search = Canvas()
    panel.add(input)
    input.add(editorChild)
    panel.add(search)
    assertTrue(TerminalInputFocus.contains(input, input))
    assertTrue(TerminalInputFocus.contains(input, editorChild))
    assertFalse(TerminalInputFocus.contains(input, search))
    assertFalse(TerminalInputFocus.contains(input, null))
  }

  @Test
  fun `handled request suppresses paired typed Tab without making another request`() {
    val sequence = TerminalTabSequence()
    var requests = 0
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', 0)) { requests++; true })
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)) { error("Duplicate request") })
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, '\t', 0)) { error("Duplicate request") })
    kotlin.test.assertEquals(1, requests)
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)) { error("Not a press") })
  }

  @Test
  fun `ordinary Tab leaves the complete native key sequence untouched`() {
    val sequence = TerminalTabSequence()
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', 0)) { false })
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)) { error("Not a press") })
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, '\t', 0)) { error("Not a press") })
  }

  @Test
  fun `unmapped presses do not cancel generation or split an owned Tab sequence`() {
    val sequence = TerminalTabSequence()
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', 0)) { true })
    for (id in listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) {
      for (character in listOf(KeyEvent.CHAR_UNDEFINED, '\u0000')) {
        val unmapped = key(id, KeyEvent.VK_UNDEFINED, character, 0)
        assertFalse(TerminalTabKey.changesInput(unmapped))
        assertFalse(sequence.dispatch(unmapped) { error("Unmapped key has no action") })
      }
    }
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)) { error("Duplicate request") })
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, '\t', 0)) { error("Duplicate request") })
  }

  @Test
  fun `real text and editing keys still invalidate pending completion`() {
    assertTrue(TerminalTabKey.changesInput(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, 'õ', 0)))
    assertTrue(TerminalTabKey.changesInput(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER, '\n', 0)))
    assertTrue(TerminalTabKey.changesInput(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_BACK_SPACE, '\b', 0)))
    assertFalse(TerminalTabKey.changesInput(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)))
  }

  @Test
  fun `lost release cannot transfer ownership to modified Tab`() {
    val sequence = TerminalTabSequence()
    assertTrue(sequence.dispatch(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', 0)) { true })
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, '\t', InputEvent.SHIFT_DOWN_MASK)) {
      error("Modified Tab must not request generation")
    })
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\t', 0)) { error("Not a press") })
    assertFalse(sequence.dispatch(key(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, '\t', 0)) { error("Not a press") })
  }

  private fun key(id: Int, code: Int, character: Char, modifiers: Int): KeyEvent =
    KeyEvent(source, id, System.currentTimeMillis(), modifiers, code, character)
}
