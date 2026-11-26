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
}
