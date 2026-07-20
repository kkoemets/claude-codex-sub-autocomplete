package com.kkoemets.subscriptionautocomplete.context

object SecretRedactor {
  private val secretName = "(?:api[_-]?key|token|secret|password|passwd|credential|authorization|cookie)"
  private val assignment = Regex(
    "(?i)^(\\s*(?:export\\s+)?[A-Z0-9_.-]*$secretName[A-Z0-9_.-]*\\s*[:=]\\s*)(.*)$",
  )
  private val structuredValue = Regex(
    "(?i)([\"']?[A-Z0-9_.-]*$secretName[A-Z0-9_.-]*[\"']?\\s*[:=]\\s*)([\"']?)([^\\s,}\\]]+)",
  )
  private val basicAuthUrl = Regex("(https?://)[^\\s/@:]+:[^\\s/@]+@", RegexOption.IGNORE_CASE)
  private val authorizationHeader = Regex(
    "(?i)(authorization\\s*[:=]\\s*(?:bearer|basic)\\s+)[^\\s,;]+",
  )
  private val providerToken = Regex(
    "(?<![A-Za-z0-9])(?:AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|" +
      "glpat-[A-Za-z0-9_-]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{20,})(?![A-Za-z0-9])",
  )
  private val jwt = Regex(
    "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])",
  )
  private val privateKey = Regex(
    "(?s)-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----.*?-----END(?: [A-Z0-9]+)? PRIVATE KEY-----",
  )

  fun redact(text: String): String = privateKey.replace(text, "<redacted-private-key>")
    .lineSequence()
    .joinToString("\n") { line ->
      val assigned = assignment.matchEntire(line)
      val redactedLine = if (assigned != null) {
        assigned.groupValues[1] + "<redacted>"
      } else {
        structuredValue.replace(line) { match -> match.groupValues[1] + "<redacted>" }
      }
      basicAuthUrl.replace(
        jwt.replace(
          providerToken.replace(
            authorizationHeader.replace(redactedLine, "${'$'}1<redacted>"),
            "<redacted-token>",
          ),
          "<redacted-jwt>",
        ),
        "${'$'}1<redacted>@",
      )
    }
}
