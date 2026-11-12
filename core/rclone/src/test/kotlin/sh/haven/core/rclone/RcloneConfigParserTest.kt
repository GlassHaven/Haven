package sh.haven.core.rclone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneConfigParserTest {

    private fun remotes(text: String) =
        (RcloneConfigParser.parse(text) as RcloneConfigParseResult.Success).remotes

    @Test
    fun `parses two remotes with type and params`() {
        val conf = """
            [gdrive]
            type = drive
            scope = drive
            token = {"access_token":"ya29"}

            [backup]
            type = s3
            provider = AWS
            access_key_id = AKIA123
        """.trimIndent()

        val r = remotes(conf)
        assertEquals(listOf("gdrive", "backup"), r.map { it.name })
        assertEquals("drive", r[0].type)
        assertEquals("drive", r[0].params["scope"])
        assertEquals("AKIA123", r[1].params["access_key_id"])
        // `type` is lifted out of params.
        assertTrue(!r[0].params.containsKey("type"))
    }

    @Test
    fun `value containing equals is kept whole`() {
        // OAuth tokens / base64 secrets carry '=' — split only on the first.
        val r = remotes("[d]\ntype = drive\ntoken = {\"a\":\"x=y==\"}")
        assertEquals("{\"a\":\"x=y==\"}", r[0].params["token"])
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val conf = """
            # my rclone config
            ; another comment

            [r]
            type = ftp
            host = example.org
        """.trimIndent()
        val r = remotes(conf)
        assertEquals(1, r.size)
        assertEquals("example.org", r[0].params["host"])
    }

    @Test
    fun `encrypted config is detected`() {
        val conf = "# Encrypted rclone configuration File\n\nRCLONE_ENCRYPT_V0:abc123def=="
        assertTrue(RcloneConfigParser.parse(conf) is RcloneConfigParseResult.Encrypted)
    }

    @Test
    fun `section without type yields empty type`() {
        val r = remotes("[weird]\nfoo = bar")
        assertEquals("", r[0].type)
        assertEquals("bar", r[0].params["foo"])
    }

    @Test
    fun `empty input yields no remotes`() {
        assertTrue(remotes("").isEmpty())
        assertTrue(remotes("   \n\n# only a comment").isEmpty())
    }

    @Test
    fun `exact duplicate section names merge with later values while case-distinct names stay separate`() {
        val conf = """
            [gdrive]
            type = drive
            scope = drive.readonly

            [GDrive]
            type = drive
            scope = drive

            [gdrive]
            type = s3
            provider = AWS
        """.trimIndent()

        val r = remotes(conf)
        assertEquals(listOf("gdrive", "GDrive"), r.map { it.name })
        assertEquals("s3", r[0].type)
        assertEquals("AWS", r[0].params["provider"])
        assertTrue(!r[0].params.containsKey("scope"))
        assertEquals("drive", r[1].type)
        assertEquals("drive", r[1].params["scope"])
    }
}
