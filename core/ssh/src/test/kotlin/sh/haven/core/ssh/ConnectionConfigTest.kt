package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionConfigTest {

    @Test
    fun `constructor accepts valid config`() {
        val config = ConnectionConfig(host = "example.com", port = 22, username = "root")
        assertEquals("example.com", config.host)
        assertEquals(22, config.port)
        assertEquals("root", config.username)
    }

    @Test
    fun `constructor defaults port to 22`() {
        val config = ConnectionConfig(host = "example.com", username = "user")
        assertEquals(22, config.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects blank host`() {
        ConnectionConfig(host = "", username = "user")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects blank username`() {
        ConnectionConfig(host = "example.com", username = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects port 0`() {
        ConnectionConfig(host = "example.com", port = 0, username = "user")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructor rejects port above 65535`() {
        ConnectionConfig(host = "example.com", port = 65536, username = "user")
    }

    @Test
    fun `constructor accepts port 1`() {
        val config = ConnectionConfig(host = "example.com", port = 1, username = "user")
        assertEquals(1, config.port)
    }

    @Test
    fun `constructor accepts port 65535`() {
        val config = ConnectionConfig(host = "example.com", port = 65535, username = "user")
        assertEquals(65535, config.port)
    }

    // Quick connect parsing

    @Test
    fun `parseQuickConnect handles user at host colon port`() {
        val config = ConnectionConfig.parseQuickConnect("root@192.168.1.1:2222")
        assertNotNull(config)
        assertEquals("root", config!!.username)
        assertEquals("192.168.1.1", config.host)
        assertEquals(2222, config.port)
    }

    @Test
    fun `parseQuickConnect handles user at host without port`() {
        val config = ConnectionConfig.parseQuickConnect("admin@server.example.com")
        assertNotNull(config)
        assertEquals("admin", config!!.username)
        assertEquals("server.example.com", config.host)
        assertEquals(22, config.port)
    }

    @Test
    fun `parseQuickConnect returns null for host without user`() {
        val config = ConnectionConfig.parseQuickConnect("example.com")
        assertNull(config)
    }

    @Test
    fun `parseQuickConnect returns null for blank input`() {
        assertNull(ConnectionConfig.parseQuickConnect(""))
        assertNull(ConnectionConfig.parseQuickConnect("   "))
    }

    @Test
    fun `parseQuickConnect trims whitespace`() {
        val config = ConnectionConfig.parseQuickConnect("  user@host:22  ")
        assertNotNull(config)
        assertEquals("user", config!!.username)
        assertEquals("host", config.host)
    }

    @Test
    fun `parseQuickConnect returns null for invalid port`() {
        assertNull(ConnectionConfig.parseQuickConnect("user@host:99999"))
    }

    @Test
    fun `parseQuickConnect handles IPv4 address`() {
        val config = ConnectionConfig.parseQuickConnect("deploy@10.0.0.1:8022")
        assertNotNull(config)
        assertEquals("10.0.0.1", config!!.host)
        assertEquals(8022, config.port)
    }

    // Auth method equality

    @Test
    fun `PrivateKey equals compares key bytes`() {
        val key1 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1, 2, 3))
        val key2 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1, 2, 3))
        val key3 = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(4, 5, 6))
        assertEquals(key1, key2)
        assert(key1 != key3)
    }

    // H3: passphrase is held as a CharArray so it can be zeroed.
    // These tests pin the contract that drove the security fix —
    // future refactors that revert PrivateKey.passphrase to String
    // (or any other immutable type) will fail compilation here.

    @Test
    fun `PrivateKey passphrase is a CharArray`() {
        val key = ConnectionConfig.AuthMethod.PrivateKey(
            keyBytes = byteArrayOf(1),
            passphrase = charArrayOf('s', 'e', 'c', 'r', 'e', 't'),
        )
        assertTrue(key.passphrase.contentEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't')))
    }

    @Test
    fun `PrivateKey String secondary constructor copies into CharArray`() {
        val key = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), "secret")
        assertTrue(key.passphrase.contentEquals(charArrayOf('s', 'e', 'c', 'r', 'e', 't')))
    }

    @Test
    fun `PrivateKey clear zeros the passphrase in place`() {
        val passphrase = charArrayOf('h', 'u', 'n', 't', 'e', 'r', '2')
        val key = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), passphrase)
        key.clear()
        // The same array the caller passed in is now zeroed — no residual
        // characters can be recovered from a heap dump after teardown.
        assertTrue(passphrase.all { it == '\u0000' })
        assertTrue(key.passphrase.all { it == '\u0000' })
    }

    @Test
    fun `PrivateKey equals compares passphrase content not reference`() {
        val a = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), charArrayOf('p', 'w'))
        val b = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), charArrayOf('p', 'w'))
        val c = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1), charArrayOf('p', 'x'))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assert(a != c)
    }

    @Test
    fun `PrivateKey defaults to empty passphrase`() {
        val key = ConnectionConfig.AuthMethod.PrivateKey(byteArrayOf(1))
        assertEquals(0, key.passphrase.size)
    }

    @Test
    fun `PrivateKey copy() preserves data class semantics for SshSessionManager deep-copy`() {
        // SshSessionManager.getConnectionConfigForProfile() relies on
        // PrivateKey.copy(keyBytes = ...) to make a defensive copy of the
        // key material before handing it to a reconnect path. Pin that
        // the data class copy() is still generated.
        val original = ConnectionConfig.AuthMethod.PrivateKey(
            keyBytes = byteArrayOf(1, 2, 3),
            passphrase = charArrayOf('p', 'w'),
        )
        val duplicate = original.copy(keyBytes = original.keyBytes.copyOf())
        assertTrue(duplicate.keyBytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(duplicate.passphrase.contentEquals(charArrayOf('p', 'w')))
        // Mutating the copy's keyBytes must not affect the original.
        duplicate.keyBytes[0] = 0
        assertEquals(1.toByte(), original.keyBytes[0])
    }

    // M3: Password.clear() pre-existed but pin its contract for completeness.

    @Test
    fun `Password clear zeros the password CharArray`() {
        val pw = charArrayOf('a', 'b', 'c')
        val auth = ConnectionConfig.AuthMethod.Password(pw)
        auth.clear()
        assertTrue(pw.all { it == '\u0000' })
        assertTrue(auth.password.all { it == '\u0000' })
    }

    @Test
    fun `Password String secondary constructor stores a CharArray`() {
        val auth = ConnectionConfig.AuthMethod.Password("hunter2")
        assertTrue(auth.password.contentEquals(charArrayOf('h', 'u', 'n', 't', 'e', 'r', '2')))
    }

    // PrivateKeys auth method

    @Test
    fun `PrivateKeys holds single key pair`() {
        val keyBytes = byteArrayOf(1, 2, 3)
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(listOf("my-key" to keyBytes))
        assertEquals(1, auth.keys.size)
        assertEquals("my-key", auth.keys[0].first)
        assertTrue(auth.keys[0].second.contentEquals(keyBytes))
    }

    @Test
    fun `PrivateKeys holds multiple key pairs`() {
        val keys = listOf(
            "work-key" to byteArrayOf(1, 2, 3),
            "personal-key" to byteArrayOf(4, 5, 6),
            "backup-key" to byteArrayOf(7, 8, 9),
        )
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(keys)
        assertEquals(3, auth.keys.size)
        assertEquals("work-key", auth.keys[0].first)
        assertEquals("personal-key", auth.keys[1].first)
        assertEquals("backup-key", auth.keys[2].first)
    }

    @Test
    fun `PrivateKeys preserves key byte content`() {
        val keyBytes = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x2D)
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(listOf("k" to keyBytes))
        assertTrue(auth.keys[0].second.contentEquals(keyBytes))
    }

    @Test
    fun `PrivateKeys with empty list holds no keys`() {
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(emptyList())
        assertEquals(0, auth.keys.size)
    }

    @Test
    fun `PrivateKeys is an AuthMethod`() {
        val auth: ConnectionConfig.AuthMethod =
            ConnectionConfig.AuthMethod.PrivateKeys(listOf("k" to byteArrayOf(1)))
        assertTrue(auth is ConnectionConfig.AuthMethod.PrivateKeys)
    }

    @Test
    fun `ConnectionConfig accepts PrivateKeys as authMethod`() {
        val auth = ConnectionConfig.AuthMethod.PrivateKeys(
            listOf("deploy" to byteArrayOf(1, 2, 3))
        )
        val config = ConnectionConfig(
            host = "example.com",
            username = "deploy",
            authMethod = auth,
        )
        assertEquals(auth, config.authMethod)
    }

    // Agent forwarding

    @Test
    fun `forwardAgent defaults to false`() {
        val config = ConnectionConfig(host = "example.com", username = "root")
        assert(!config.forwardAgent)
        assertEquals(0, config.agentIdentities.size)
    }

    @Test
    fun `forwardAgent carries agent identities`() {
        val keys = listOf(
            "work" to byteArrayOf(1, 2, 3),
            "personal" to byteArrayOf(4, 5, 6),
        )
        val config = ConnectionConfig(
            host = "example.com",
            username = "root",
            forwardAgent = true,
            agentIdentities = keys,
        )
        assertTrue(config.forwardAgent)
        assertEquals(2, config.agentIdentities.size)
        assertEquals("work", config.agentIdentities[0].first)
        assertTrue(config.agentIdentities[1].second.contentEquals(byteArrayOf(4, 5, 6)))
    }
}
