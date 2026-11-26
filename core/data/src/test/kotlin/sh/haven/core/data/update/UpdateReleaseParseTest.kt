package sh.haven.core.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for #874 Slice 1:
 *  - F-Droid signing-certificate channel detection ([UpdateChecker.Channel.FDROID])
 *  - GitHub `/releases/latest` JSON parsing including markdown `body` (`releaseNotes`)
 *    and null-body handling.
 */
class UpdateReleaseParseTest {

    private val githubDigest = "ea03a3a70e1c11d0a78932f959b21f20d8735d9cd750997657cb7f7d7c2b90b3"
    private val fdroidDigest = "ea05a89431961b8ac53c36725452673c2be1c2d7b6e48771617b974e6092b332"
    private val debugDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `resolveChannel identifies GitHub release, F-Droid, and self-built signers`() {
        assertEquals(
            UpdateChecker.Channel.GITHUB_RELEASE,
            UpdateChecker.resolveChannel(listOf(githubDigest)),
        )
        assertEquals(
            UpdateChecker.Channel.GITHUB_RELEASE,
            UpdateChecker.resolveChannel(listOf(githubDigest.uppercase())),
        )
        assertEquals(
            UpdateChecker.Channel.FDROID,
            UpdateChecker.resolveChannel(listOf(fdroidDigest)),
        )
        assertEquals(
            UpdateChecker.Channel.FDROID,
            UpdateChecker.resolveChannel(listOf(fdroidDigest.uppercase())),
        )
        assertEquals(
            UpdateChecker.Channel.OTHER,
            UpdateChecker.resolveChannel(listOf(debugDigest)),
        )
        assertEquals(
            UpdateChecker.Channel.OTHER,
            UpdateChecker.resolveChannel(emptyList()),
        )
    }

    @Test
    fun `parseReleaseJson extracts releaseNotes and html_url when newer`() {
        val body = """
            {
              "tag_name": "v5.94.77",
              "html_url": "https://github.com/GlassHaven/Haven/releases/tag/v5.94.77",
              "body": "### Fixes\n- Show changelog in Settings -> About (#874)\n"
            }
        """.trimIndent()

        val result = UpdateChecker.parseReleaseJson(body, installed = "5.94.76")
        assertTrue("expected Available, got $result", result is UpdateChecker.Result.Available)
        val available = result as UpdateChecker.Result.Available
        assertEquals("5.94.77", available.version)
        assertEquals("https://github.com/GlassHaven/Haven/releases/tag/v5.94.77", available.releaseUrl)
        assertEquals("### Fixes\n- Show changelog in Settings -> About (#874)", available.releaseNotes)
    }

    @Test
    fun `parseReleaseJson treats JSON null body as empty string rather than literal null`() {
        val body = """
            {
              "tag_name": "v5.94.77",
              "body": null
            }
        """.trimIndent()

        val result = UpdateChecker.parseReleaseJson(body, installed = "5.94.76")
        assertTrue("expected Available, got $result", result is UpdateChecker.Result.Available)
        val available = result as UpdateChecker.Result.Available
        assertEquals("5.94.77", available.version)
        assertEquals("https://github.com/GlassHaven/Haven/releases/tag/v5.94.77", available.releaseUrl)
        assertEquals("", available.releaseNotes)
    }

    @Test
    fun `parseReleaseJson returns UpToDate when installed matches or exceeds latest`() {
        val body = """{"tag_name": "v5.94.76", "body": "Notes"}"""
        assertEquals(
            UpdateChecker.Result.UpToDate("5.94.76"),
            UpdateChecker.parseReleaseJson(body, installed = "5.94.76"),
        )
        assertEquals(
            UpdateChecker.Result.UpToDate("5.94.77"),
            UpdateChecker.parseReleaseJson(body, installed = "5.94.77"),
        )
    }

    @Test
    fun `parseReleaseJson fails cleanly on malformed JSON or missing tag`() {
        val badJson = UpdateChecker.parseReleaseJson("not-json", installed = "5.94.76")
        assertTrue(badJson is UpdateChecker.Result.Failed)

        val missingTag = UpdateChecker.parseReleaseJson("""{"body": "no tag"}""", installed = "5.94.76")
        assertTrue(missingTag is UpdateChecker.Result.Failed)
    }

    @Test
    fun `resolveFlavour and resolveAbiTag map runtime capabilities to release asset coordinates`() {
        assertEquals(
            UpdateChecker.Flavour.FULL,
            UpdateChecker.resolveFlavour(anyDesktop = true, rclone = true),
        )
        assertEquals(
            UpdateChecker.Flavour.STORAGE,
            UpdateChecker.resolveFlavour(anyDesktop = false, rclone = true),
        )
        assertEquals(
            UpdateChecker.Flavour.TERMINAL,
            UpdateChecker.resolveFlavour(anyDesktop = false, rclone = false),
        )

        assertEquals("arm64", UpdateChecker.resolveAbiTag(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("armv7", UpdateChecker.resolveAbiTag(listOf("armeabi-v7a", "armeabi")))
        assertEquals("x64", UpdateChecker.resolveAbiTag(listOf("x86_64", "arm64-v8a")))
        assertEquals(null, UpdateChecker.resolveAbiTag(listOf("riscv64")))

        assertEquals(
            "haven-5.94.77-arm64-release.apk",
            UpdateChecker.expectedApkAssetName("5.94.77", "arm64", UpdateChecker.Flavour.FULL),
        )
        assertEquals(
            "haven-5.94.77-arm64-storage-release.apk",
            UpdateChecker.expectedApkAssetName("5.94.77", "arm64", UpdateChecker.Flavour.STORAGE),
        )
        assertEquals(
            "haven-5.94.77-x64-terminal-release.apk",
            UpdateChecker.expectedApkAssetName("5.94.77", "x64", UpdateChecker.Flavour.TERMINAL),
        )
    }

    @Test
    fun `parseReleaseJson selects matching ABI and flavour APK plus SHA256SUMS and rejects untrusted URLs`() {
        val body = """
            {
              "tag_name": "v5.94.77",
              "html_url": "https://github.com/GlassHaven/Haven/releases/tag/v5.94.77",
              "body": "Notes",
              "assets": [
                {
                  "name": "haven-5.94.77-arm64-release.apk",
                  "browser_download_url": "https://github.com/GlassHaven/Haven/releases/download/v5.94.77/haven-5.94.77-arm64-release.apk"
                },
                {
                  "name": "haven-5.94.77-arm64-storage-release.apk",
                  "browser_download_url": "https://github.com/GlassHaven/Haven/releases/download/v5.94.77/haven-5.94.77-arm64-storage-release.apk"
                },
                {
                  "name": "SHA256SUMS",
                  "browser_download_url": "https://github.com/GlassHaven/Haven/releases/download/v5.94.77/SHA256SUMS"
                }
              ]
            }
        """.trimIndent()

        val storage = UpdateChecker.parseReleaseJson(
            body = body,
            installed = "5.94.76",
            abiTag = "arm64",
            flavour = UpdateChecker.Flavour.STORAGE,
        ) as UpdateChecker.Result.Available
        assertEquals("haven-5.94.77-arm64-storage-release.apk", storage.apkAssetName)
        assertEquals(
            "https://github.com/GlassHaven/Haven/releases/download/v5.94.77/haven-5.94.77-arm64-storage-release.apk",
            storage.apkDownloadUrl,
        )
        assertEquals(
            "https://github.com/GlassHaven/Haven/releases/download/v5.94.77/SHA256SUMS",
            storage.sha256SumsUrl,
        )

        // x64 nightly asset not uploaded yet -> still Available, but apkDownloadUrl is null so UI falls back to release page
        val pendingX64 = UpdateChecker.parseReleaseJson(
            body = body,
            installed = "5.94.76",
            abiTag = "x64",
            flavour = UpdateChecker.Flavour.FULL,
        ) as UpdateChecker.Result.Available
        assertEquals(null, pendingX64.apkDownloadUrl)

        // Untrusted third-party download host is ignored
        val untrustedBody = body.replace(
            "https://github.com/GlassHaven/Haven/releases/download/v5.94.77/haven-5.94.77-arm64-release.apk",
            "https://evil.example/haven-5.94.77-arm64-release.apk",
        )
        val untrusted = UpdateChecker.parseReleaseJson(
            body = untrustedBody,
            installed = "5.94.76",
            abiTag = "arm64",
            flavour = UpdateChecker.Flavour.FULL,
        ) as UpdateChecker.Result.Available
        assertEquals(null, untrusted.apkDownloadUrl)
    }

    @Test
    fun `parseSha256Sums extracts exact matching digest and rejects missing or malformed entries`() {
        val digestA = "a".repeat(64)
        val digestB = "B".repeat(64)
        val sums = """
            $digestA  haven-5.94.77-arm64-release.apk
            $digestB *haven-5.94.77-arm64-terminal-release.apk
            not_a_hex  haven-5.94.77-arm64-storage-release.apk
        """.trimIndent()

        assertEquals(digestA, UpdateChecker.parseSha256Sums(sums, "haven-5.94.77-arm64-release.apk"))
        assertEquals(digestB.lowercase(), UpdateChecker.parseSha256Sums(sums, "haven-5.94.77-arm64-terminal-release.apk"))
        assertEquals(null, UpdateChecker.parseSha256Sums(sums, "haven-5.94.77-arm64-storage-release.apk"))
        assertEquals(null, UpdateChecker.parseSha256Sums(sums, "haven-5.94.77-x64-release.apk"))
    }

    @Test
    fun `stageAndVerifyApk enforces content length, SHA256SUMS, and release signer digest`() {
        val dir = java.nio.file.Files.createTempDirectory("haven-update-test").toFile()
        try {
            val payload = "pretend-apk-bytes-v5.94.77".toByteArray()
            val expectedSha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }

            // 1. Happy path: exact length + matching SHA-256 + matching release signer
            val ok = UpdateChecker.stageAndVerifyApk(
                updatesDir = dir,
                assetName = "haven-5.94.77-arm64-release.apk",
                expectedSha256 = expectedSha,
                expectedLength = payload.size.toLong(),
                input = payload.inputStream(),
                signerDigestsOf = { listOf(githubDigest) },
            )
            assertTrue("expected Ready, got $ok", ok is UpdateChecker.DownloadResult.Ready)
            val ready = ok as UpdateChecker.DownloadResult.Ready
            assertTrue(ready.apkFile.isFile)
            assertEquals(expectedSha, ready.sha256)

            // 2. Truncated stream (advertised length > actual bytes) -> Failed and .part deleted
            val truncated = UpdateChecker.stageAndVerifyApk(
                updatesDir = dir,
                assetName = "haven-5.94.78-arm64-release.apk",
                expectedSha256 = expectedSha,
                expectedLength = payload.size.toLong() + 10L,
                input = payload.inputStream(),
                signerDigestsOf = { listOf(githubDigest) },
            )
            assertTrue("expected Failed on truncated stream, got $truncated", truncated is UpdateChecker.DownloadResult.Failed)
            assertEquals(false, java.io.File(dir, "haven-5.94.78-arm64-release.apk").exists())
            assertEquals(false, java.io.File(dir, "haven-5.94.78-arm64-release.apk.part").exists())

            // 3. SHA-256 mismatch -> Failed and .part deleted
            val wrongSha = UpdateChecker.stageAndVerifyApk(
                updatesDir = dir,
                assetName = "haven-5.94.78-arm64-release.apk",
                expectedSha256 = "f".repeat(64),
                expectedLength = payload.size.toLong(),
                input = payload.inputStream(),
                signerDigestsOf = { listOf(githubDigest) },
            )
            assertTrue("expected Failed on wrong SHA-256, got $wrongSha", wrongSha is UpdateChecker.DownloadResult.Failed)
            assertEquals(false, java.io.File(dir, "haven-5.94.78-arm64-release.apk.part").exists())

            // 4. Wrong APK signer (e.g. debug or tampered cert) -> Failed and .part deleted
            val wrongSigner = UpdateChecker.stageAndVerifyApk(
                updatesDir = dir,
                assetName = "haven-5.94.78-arm64-release.apk",
                expectedSha256 = expectedSha,
                expectedLength = payload.size.toLong(),
                input = payload.inputStream(),
                signerDigestsOf = { listOf(debugDigest) },
            )
            assertTrue("expected Failed on wrong signer, got $wrongSigner", wrongSigner is UpdateChecker.DownloadResult.Failed)
            assertEquals(false, java.io.File(dir, "haven-5.94.78-arm64-release.apk").exists())
            assertEquals(false, java.io.File(dir, "haven-5.94.78-arm64-release.apk.part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
