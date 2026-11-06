package com.codeagent.android.agent

/** Filename/path rules for preventing common local credentials from entering project exports. */
internal object ProjectExportRules {
    private val sensitiveExactNames = setOf(
        "local.properties",
        ".npmrc",
        ".pypirc",
        ".netrc",
        "id_rsa",
        "id_ed25519"
    )

    private val sensitiveExtensions = setOf(
        ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"
    )

    fun isSensitivePath(relativePath: String): Boolean {
        val segments = relativePath.replace(92.toChar(), '/').split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return true
        return segments.any { segment ->
            val name = segment.lowercase()
            name in sensitiveExactNames ||
                name == ".env" || name.startsWith(".env.") ||
                name == "credentials" || name.startsWith("credentials.") ||
                name == "secrets" || name.startsWith("secrets.") ||
                sensitiveExtensions.any { name.endsWith(it) }
        }
    }
}
