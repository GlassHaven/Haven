package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMcpServerConfigTest {

    @Test
    fun `encodeList encrypts non-blank bearer tokens and decodeList restores them`() {
        val servers = listOf(
            ChatMcpServerConfig(
                id = "s1",
                name = "Home Assistant",
                url = "https://ha.example/mcp",
                bearerToken = "secret-ha-token",
                enabled = true,
            ),
            ChatMcpServerConfig(
                id = "s2",
                name = "Local KiCad",
                url = "http://127.0.0.1:8910/mcp",
                bearerToken = null,
                enabled = false,
            ),
        )
        val encoded = ChatMcpServerConfig.encodeList(servers) { "ENC:$it" }
        assertTrue(encoded.contains("ENC:secret-ha-token"))

        val decoded = ChatMcpServerConfig.decodeList(encoded) { cipher ->
            cipher.removePrefix("ENC:").takeIf { cipher.startsWith("ENC:") }
        }
        assertEquals(servers, decoded)
    }

    @Test
    fun `decodeList drops unreadable tokens without dropping the server entry`() {
        val raw = """[{"id":"s1","name":"Srv","url":"https://srv/mcp","bearerToken":"ENC:broken","enabled":true}]"""
        val decoded = ChatMcpServerConfig.decodeList(raw) { null }
        assertEquals(1, decoded.size)
        assertEquals("Srv", decoded[0].name)
        assertNull(decoded[0].bearerToken)
    }

    @Test
    fun `decodeList handles blank or malformed JSON gracefully`() {
        assertTrue(ChatMcpServerConfig.decodeList(null) { it }.isEmpty())
        assertTrue(ChatMcpServerConfig.decodeList("") { it }.isEmpty())
        assertTrue(ChatMcpServerConfig.decodeList("{not-an-array") { it }.isEmpty())
    }
}
