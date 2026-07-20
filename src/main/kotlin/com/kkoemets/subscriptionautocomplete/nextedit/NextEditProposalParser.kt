package com.kkoemets.subscriptionautocomplete.nextedit

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser

internal object NextEditProposalParser {
  fun parse(raw: String, targets: List<NextEditTarget>): NextEditParseResult {
    val response = raw.trim()
    if (response.isEmpty()) return NextEditParseResult.Rejected(NextEditRejectionReason.EMPTY)
    if (response.length > MAX_RESPONSE_CHARS) {
      return NextEditParseResult.Rejected(NextEditRejectionReason.TOO_LARGE)
    }
    val root = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull()
      ?: return NextEditParseResult.Rejected(NextEditRejectionReason.INVALID_JSON)
    if (root.keySet() != ROOT_KEYS || root.get("version")?.strictVersion() != SCHEMA_VERSION) {
      return NextEditParseResult.Rejected(NextEditRejectionReason.INVALID_SCHEMA)
    }
    val summary = root.string("summary") ?: return invalidSchema()
    if (!summary.validText(MAX_SUMMARY_CHARS, allowEmpty = true)) return invalidText()
    val editsValue = root.get("edits")?.takeIf(JsonElement::isJsonArray) ?: return invalidSchema()
    val edits = editsValue.asJsonArray
    if (edits.size() > MAX_EDITS) {
      return NextEditParseResult.Rejected(NextEditRejectionReason.TOO_MANY_EDITS)
    }
    val targetById = targets.associateBy(NextEditTarget::id)
    val proposals = ArrayList<NextEditProposalItem>(edits.size())
    var totalCharacters = 0
    for (element in edits) {
      val edit = runCatching { element.asJsonObject }.getOrNull() ?: return invalidSchema()
      if (edit.keySet() != EDIT_KEYS) return invalidSchema()
      val targetId = edit.string("target") ?: return invalidSchema()
      val before = edit.string("before") ?: return invalidSchema()
      val after = edit.string("after") ?: return invalidSchema()
      val rationale = edit.string("rationale") ?: return invalidSchema()
      if (
        !targetId.validText(MAX_TARGET_ID_CHARS) ||
        !before.validText(MAX_EDIT_CHARS) ||
        !after.validText(MAX_EDIT_CHARS, allowEmpty = true) ||
        !rationale.validText(MAX_RATIONALE_CHARS, allowEmpty = true) ||
        before.lineSequence().count() > MAX_EDIT_LINES ||
        after.lineSequence().count() > MAX_EDIT_LINES ||
        before == after
      ) {
        return invalidText()
      }
      totalCharacters += before.length + after.length + rationale.length
      if (totalCharacters > MAX_TOTAL_EDIT_CHARS) {
        return NextEditParseResult.Rejected(NextEditRejectionReason.TOO_LARGE)
      }
      val target = targetById[targetId]
        ?: return NextEditParseResult.Rejected(NextEditRejectionReason.UNKNOWN_TARGET)
      if (!target.excerpt.contains(before) || !target.currentText.contains(before)) {
        return NextEditParseResult.Rejected(NextEditRejectionReason.UNANCHORED_EDIT)
      }
      if (occurrences(target.currentText, before) != 1) {
        return NextEditParseResult.Rejected(NextEditRejectionReason.AMBIGUOUS_EDIT)
      }
      proposals += NextEditProposalItem(
        targetId = targetId,
        targetName = target.displayName,
        targetFile = target.file,
        targetFileIdentity = target.fileIdentity,
        targetModificationStamp = target.modificationStamp,
        before = before,
        after = after,
        rationale = rationale,
      )
    }
    return NextEditParseResult.Success(NextEditProposal(summary, proposals))
  }

  private fun JsonObject.string(name: String): String? {
    val value = get(name) ?: return null
    return (value.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive)
      ?.takeIf(JsonPrimitive::isString)
      ?.asString
  }

  private fun JsonElement.strictVersion(): Int? = takeIf(JsonElement::isJsonPrimitive)
    ?.asJsonPrimitive
    ?.takeIf { value -> value.isNumber && value.asString == SCHEMA_VERSION.toString() }
    ?.asInt

  private fun String.validText(maxCharacters: Int, allowEmpty: Boolean = false): Boolean {
    if ((!allowEmpty && isBlank()) || length > maxCharacters) return false
    return none { character -> character == '\u0000' || character == '\u007f' || character < ' ' && character !in "\n\r\t" }
  }

  private fun occurrences(text: String, value: String): Int {
    var count = 0
    var offset = text.indexOf(value)
    while (offset >= 0) {
      count += 1
      if (count > 1) return count
      offset = text.indexOf(value, offset + value.length)
    }
    return count
  }

  private fun invalidSchema() = NextEditParseResult.Rejected(NextEditRejectionReason.INVALID_SCHEMA)

  private fun invalidText() = NextEditParseResult.Rejected(NextEditRejectionReason.INVALID_TEXT)

  private val ROOT_KEYS = setOf("version", "summary", "edits")
  private val EDIT_KEYS = setOf("target", "before", "after", "rationale")
  private const val SCHEMA_VERSION = 1
  private const val MAX_RESPONSE_CHARS = 24_000
  private const val MAX_SUMMARY_CHARS = 400
  private const val MAX_RATIONALE_CHARS = 600
  private const val MAX_TARGET_ID_CHARS = 32
  private const val MAX_EDIT_CHARS = 6_000
  private const val MAX_TOTAL_EDIT_CHARS = 12_000
  private const val MAX_EDIT_LINES = 120
  private const val MAX_EDITS = 3
}
