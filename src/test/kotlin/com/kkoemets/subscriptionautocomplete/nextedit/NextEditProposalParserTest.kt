package com.kkoemets.subscriptionautocomplete.nextedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NextEditProposalParserTest {
  @Test
  fun `valid proposal is anchored to an allowed target`() {
    val result = NextEditProposalParser.parse(
      """{"version":1,"summary":"Update the caller","edits":[{"target":"target-1","before":"callOld()","after":"callNew()","rationale":"Match the renamed API"}]}""",
      listOf(target("target-1", "before\ncallOld()\nafter", stamp = 91)),
    )

    val proposal = assertIs<NextEditParseResult.Success>(result).proposal
    assertEquals("Update the caller", proposal.summary)
    assertEquals("callNew()", proposal.edits.single().after)
    assertEquals(91, proposal.edits.single().targetModificationStamp)
  }

  @Test
  fun `unknown and unanchored targets are rejected`() {
    val target = target("target-1", "callOld()")
    val unknown = NextEditProposalParser.parse(
      json("target-2", "callOld()", "callNew()"),
      listOf(target),
    )
    val unanchored = NextEditProposalParser.parse(
      json("target-1", "missing()", "callNew()"),
      listOf(target),
    )

    assertEquals(
      NextEditRejectionReason.UNKNOWN_TARGET,
      assertIs<NextEditParseResult.Rejected>(unknown).reason,
    )
    assertEquals(
      NextEditRejectionReason.UNANCHORED_EDIT,
      assertIs<NextEditParseResult.Rejected>(unanchored).reason,
    )
  }

  @Test
  fun `ambiguous before text is rejected`() {
    val result = NextEditProposalParser.parse(
      json("target-1", "same()", "changed()"),
      listOf(target("target-1", "same()\nother()\nsame()")),
    )

    assertEquals(
      NextEditRejectionReason.AMBIGUOUS_EDIT,
      assertIs<NextEditParseResult.Rejected>(result).reason,
    )
  }

  @Test
  fun `schema is strict and supports empty proposals`() {
    val extraKey = NextEditProposalParser.parse(
      """{"version":1,"summary":"x","edits":[],"path":"/tmp/source"}""",
      emptyList(),
    )
    val empty = NextEditProposalParser.parse(
      """{"version":1,"summary":"No clear next edit","edits":[]}""",
      emptyList(),
    )

    assertEquals(
      NextEditRejectionReason.INVALID_SCHEMA,
      assertIs<NextEditParseResult.Rejected>(extraKey).reason,
    )
    assertEquals(0, assertIs<NextEditParseResult.Success>(empty).proposal.edits.size)
  }

  @Test
  fun `markdown fences and prose are rejected`() {
    val fenced = NextEditProposalParser.parse(
      """```json
        |${json("target-1", "old()", "new()")}
        |```""".trimMargin(),
      listOf(target("target-1", "old()")),
    )
    val prose = NextEditProposalParser.parse(
      "Here is the proposal: ${json("target-1", "old()", "new()")}",
      listOf(target("target-1", "old()")),
    )

    assertEquals(
      NextEditRejectionReason.INVALID_JSON,
      assertIs<NextEditParseResult.Rejected>(fenced).reason,
    )
    assertEquals(
      NextEditRejectionReason.INVALID_JSON,
      assertIs<NextEditParseResult.Rejected>(prose).reason,
    )
  }

  @Test
  fun `schema version and strings are strict and active target is unavailable`() {
    val target = target("target-1", "old()")
    val wrongVersion = NextEditProposalParser.parse(
      """{"version":2,"summary":"x","edits":[]}""",
      listOf(target),
    )
    val coercedString = NextEditProposalParser.parse(
      """{"version":1,"summary":12,"edits":[]}""",
      listOf(target),
    )
    val activeTarget = NextEditProposalParser.parse(
      json("target-0", "old()", "new()"),
      listOf(target),
    )

    assertEquals(
      NextEditRejectionReason.INVALID_SCHEMA,
      assertIs<NextEditParseResult.Rejected>(wrongVersion).reason,
    )
    assertEquals(
      NextEditRejectionReason.INVALID_SCHEMA,
      assertIs<NextEditParseResult.Rejected>(coercedString).reason,
    )
    assertEquals(
      NextEditRejectionReason.UNKNOWN_TARGET,
      assertIs<NextEditParseResult.Rejected>(activeTarget).reason,
    )
  }

  private fun json(target: String, before: String, after: String): String =
    """{"version":1,"summary":"x","edits":[{"target":"$target","before":"$before","after":"$after","rationale":"reason"}]}"""

  private fun target(id: String, text: String, stamp: Long = 1) = NextEditTarget(
    id = id,
    displayName = "related.ts",
    languageId = "TypeScript",
    excerpt = text,
    currentText = text,
    modificationStamp = stamp,
    file = null,
  )
}
