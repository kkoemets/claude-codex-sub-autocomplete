package com.kkoemets.subscriptionautocomplete.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.vfs.VirtualFile

class ContextFilePolicy(private val project: Project) {
  fun isEligible(file: VirtualFile): Boolean {
    if (!file.isValid || file.isDirectory || file.fileType.isBinary) return false
    if (file.length > MAX_FILE_BYTES) return false
    if (!ProjectFileIndex.getInstance(project).isInContent(file)) return false
    if (GeneratedSourcesFilter.findFirstMatchingFilter(file, project) != null) return false
    val name = file.name.lowercase()
    if (name.startsWith(".env") || name in EXACT_SENSITIVE_NAMES) return false
    if (SENSITIVE_NAME_PARTS.any(name::contains)) return false
    val extension = file.extension?.lowercase().orEmpty()
    return extension !in SENSITIVE_EXTENSIONS
  }

  companion object {
    private const val MAX_FILE_BYTES = 1_000_000L
    private val EXACT_SENSITIVE_NAMES = setOf(
      "credentials",
      "credentials.json",
      "secrets",
      "secrets.json",
      ".npmrc",
      ".pypirc",
      ".netrc",
    )
    private val SENSITIVE_NAME_PARTS = setOf("credential", "secret", "private-key", "private_key")
    private val SENSITIVE_EXTENSIONS = setOf("pem", "key", "p12", "pfx", "jks", "keystore")
  }
}
