package com.kkoemets.subscriptionautocomplete.context

/**
 * Removes credentials from text before it leaves the IDE.
 *
 * Two goals pull against each other: every recognisable secret must go, and ordinary code must
 * survive, because the text right before the caret is what the model continues. Values are
 * therefore redacted by their shape (provider token formats, URLs with passwords, key blocks) or
 * when a secret-named key is given a literal; expressions such as `settings.max` or `getToken()`
 * are code, not secrets, and are left alone.
 */
object SecretRedactor {
  private const val SECRET_NAME =
    "(?:api[_-]?key|token|secret|password|passwd|passphrase|credential|authorization|cookie|webhook|dsn|" +
      "(?:access|private|signing|encryption|master|license|client)[_-]?key|connection[_-]?string)"

  /** `NAME = value` or `NAME: value` as a whole line, as in `.env`, properties and YAML files. */
  private val assignment = Regex(
    "(?i)^(\\s*(?:export\\s+)?[\"']?[A-Z0-9_.-]*$SECRET_NAME[A-Z0-9_.-]*[\"']?\\s*(?:=|:(?!:))\\s*)(.*)$",
  )

  /** A secret-named key given a quoted literal anywhere in a line: `{"token": "..."}`, `password="..."`. */
  private val quotedValue = Regex(
    "(?i)([\"']?[A-Z0-9_.-]*$SECRET_NAME[A-Z0-9_.-]*[\"']?\\s*(?:=(?!=)|:(?!:))\\s*)([\"'])([^\"'\\n]+)\\2",
  )

  /** Credentials embedded in any URL scheme: `postgres://user:password@host`, `https://user:token@...`. */
  private val urlCredentials = Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@:]+:[^\\s/@]+@")
  private val authorizationHeader = Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic|token)\\s+)[^\\s,;\"']+")
  private val providerToken = Regex(
    "(?<![A-Za-z0-9])(?:" +
      "(?:AKIA|ASIA)[0-9A-Z]{16}|" +
      "gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{20,}|" +
      "sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{20,}|(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}|" +
      "AIza[0-9A-Za-z_-]{35}|xox[abposr]-[A-Za-z0-9-]{10,}|npm_[A-Za-z0-9]{36}|" +
      "hf_[A-Za-z0-9]{30,}" +
      ")(?![A-Za-z0-9])",
  )
  private val webhookUrl = Regex(
    "(?i)https://(?:hooks\\.slack\\.com/services|discord(?:app)?\\.com/api/webhooks)/[A-Za-z0-9/_-]+",
  )

  /** JSON Web Tokens always start with a base64url-encoded `{"`. Plain dotted names are not tokens. */
  private val jwt = Regex("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
  private val privateKey = Regex(
    "(?s)-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)* PRIVATE KEY-----",
  )
  private val privateKeyBegin = Regex("-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----")
  private val privateKeyEnd = Regex("-----END(?: [A-Z0-9]+)* PRIVATE KEY-----")

  /** A bare line of base64, such as the body of a key block whose delimiters fall outside the text. */
  private val base64Line = Regex("^\\s*[A-Za-z0-9+/]{60,}={0,2}\\s*$")

  /** Right-hand sides that are code rather than a literal: calls, member chains, lookups, keywords. */
  private val expression = Regex(
    "^(?:[A-Za-z_$][\\w$]*(?:\\??\\.[A-Za-z_$][\\w$]*)+.*|.*[(\\[].*|" +
      "(?:null|nil|None|true|false|undefined|this|self)\\b.*)$",
  )

  /** `password: String,` in a parameter list or interface is a type, not a value. */
  private val typeAnnotation = Regex(
    "^(?:String|string|str|CharSequence|Int|int|Long|long|number|Boolean|boolean|bool|Any|any|unknown|" +
      "ByteArray|bytes|char\\[]|Optional<[^>]*>|SecureString)\\??\\s*[,;)=]?.*$",
  )

  fun redact(text: String): String = redactPrivateKeys(text)
    .lineSequence()
    .joinToString("\n", transform = ::redactLine)

  /**
   * A complete key block is replaced as a whole. Text that was cut out of a larger document, such
   * as the code before or after the caret, can hold only one of its delimiters: everything after an
   * opening delimiter, or before a closing one, is key material.
   */
  private fun redactPrivateKeys(text: String): String {
    var redacted = privateKey.replace(text, "<redacted-private-key>")
    privateKeyBegin.find(redacted)?.let { begin ->
      redacted = redacted.substring(0, begin.range.first) + "<redacted-private-key>"
    }
    privateKeyEnd.find(redacted)?.let { end ->
      redacted = "<redacted-private-key>" + redacted.substring(end.range.last + 1)
    }
    return redacted
  }

  private fun redactLine(line: String): String {
    if (base64Line.matches(line)) return line.substringBefore(line.trim()) + "<redacted-base64>"
    var redacted = authorizationHeader.replace(line, "$1<redacted>")
    val assigned = assignment.matchEntire(redacted)
    redacted = if (assigned != null && isLiteral(assigned.groupValues[2])) {
      assigned.groupValues[1] + "<redacted>"
    } else {
      quotedValue.replace(redacted) { match ->
        match.groupValues[1] + match.groupValues[2] + "<redacted>" + match.groupValues[2]
      }
    }
    redacted = webhookUrl.replace(redacted, "<redacted-webhook>")
    redacted = providerToken.replace(redacted, "<redacted-token>")
    redacted = jwt.replace(redacted, "<redacted-jwt>")
    return urlCredentials.replace(redacted, "$1<redacted>@")
  }

  /**
   * True when the right-hand side of a secret-named assignment is a value worth hiding. An empty
   * value is the user still typing, so replacing it would hand the model `<redacted>` to continue.
   */
  private fun isLiteral(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed == "\"\"" || trimmed == "''") return false
    if (trimmed.first() == '"' || trimmed.first() == '\'') return true
    return !expression.matches(trimmed) && !typeAnnotation.matches(trimmed)
  }
}
