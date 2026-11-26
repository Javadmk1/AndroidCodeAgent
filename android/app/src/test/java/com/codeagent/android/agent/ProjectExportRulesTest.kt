package com.codeagent.android.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectExportRulesTest {
    @Test
    fun excludesCommonEnvironmentAndLocalCredentialFiles() {
        listOf(
            ".env",
            ".env.production",
            "local.properties",
            "app/local.properties",
            "signing/release.jks",
            "upload.keystore",
            "certs/client.p12",
            "certs/client.pem",
            "private/id_rsa",
            ".npmrc",
            "config/credentials.json",
            "config/secrets.yaml",
            ".ssh/id_ed25519",
            ".codeagent-history/old-source.kt",
            "config/service-account-prod.json",
            "app/.env.production.local",
            "signing/release.mobileprovision"
        ).forEach { path -> assertTrue("$path must be excluded", ProjectExportRules.isSensitivePath(path)) }
    }

    @Test
    fun keepsOrdinaryProjectFiles() {
        listOf(
            "README.md",
            "app/build.gradle.kts",
            "src/MainActivity.kt",
            "gradle/libs.versions.toml",
            "assets/logo.png"
        ).forEach { path -> assertFalse("$path must remain exportable", ProjectExportRules.isSensitivePath(path)) }
    }

    @Test
    fun rejectsMalformedRelativePaths() {
        assertTrue(ProjectExportRules.isSensitivePath("../outside.txt"))
        assertTrue(ProjectExportRules.isSensitivePath("src//Main.kt"))
        assertTrue(ProjectExportRules.isSensitivePath("src/./Main.kt"))
    }
}
