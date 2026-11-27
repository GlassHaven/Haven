package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshOptionsApplierTest {

    /** [SshOptionsApplier.Target] backed by an in-memory map seeded with
     *  mwiede/jsch 2.28.0's actual default proposals so the +/-/^ math
     *  exercises realistic values. */
    private class FakeTarget(seed: Map<String, String>) : SshOptionsApplier.Target {
        val store: MutableMap<String, String> = seed.toMutableMap()
        var aliveIntervalMs: Int? = null
        var aliveCountMax: Int? = null
        override fun getConfig(key: String): String? = store[key]
        override fun setConfig(key: String, value: String) { store[key] = value }
        override fun setServerAliveInterval(intervalMs: Int) { aliveIntervalMs = intervalMs }
        override fun setServerAliveCountMax(count: Int) { aliveCountMax = count }
    }

    private fun defaultTarget() = FakeTarget(
        mapOf(
            // Captured from jsch-2.28.0.jar (com.jcraft.jsch.JSch defaults).
            "kex" to "mlkem768x25519-sha256,curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256",
            "cipher.c2s" to "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr",
            "cipher.s2c" to "chacha20-poly1305@openssh.com,aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr,aes192-ctr,aes128-ctr",
            "mac.c2s" to "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha1-etm@openssh.com,hmac-sha2-256,hmac-sha2-512,hmac-sha1",
            "mac.s2c" to "hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,hmac-sha1-etm@openssh.com,hmac-sha2-256,hmac-sha2-512,hmac-sha1",
            "server_host_key" to "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256",
            "PubkeyAcceptedAlgorithms" to "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256",
            "PreferredAuthentications" to "publickey,keyboard-interactive,password",
            "StrictHostKeyChecking" to "no",
        )
    )

    // --- The issue #155 regression test ---

    @Test
    fun `KexAlgorithms with + appends legacy SHA-1 KEX to the kex proposal`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf(
            "KexAlgorithms" to "+diffie-hellman-group-exchange-sha1,diffie-hellman-group14-sha1,diffie-hellman-group1-sha1",
        ))
        val kex = target.store["kex"]!!
        // Defaults are still first — Haven's modern set is preserved.
        assertTrue("modern KEX kept: $kex", kex.startsWith("mlkem768x25519-sha256,"))
        // The legacy algorithms are now in the proposal.
        assertTrue("group14-sha1 present: $kex", "diffie-hellman-group14-sha1" in kex.split(","))
        assertTrue("group1-sha1 present: $kex", "diffie-hellman-group1-sha1" in kex.split(","))
        assertTrue("gex-sha1 present: $kex", "diffie-hellman-group-exchange-sha1" in kex.split(","))
        // The bogus "KexAlgorithms" key is NOT set as-is.
        assertFalse("OpenSSH key name leaked", "KexAlgorithms" in target.store)
    }

    @Test
    fun `Haven-internal directives never reach JSch config`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf(
            "HavenSshEngine" to "sshlib",
            "havensshengine" to "sshlib",
            "HavenForwardX11" to "yes",
            "HavenForwardPulseAudio" to "yes",
            "ForwardX11" to "yes",
            "ForwardX11Trusted" to "yes",
            "ForwardPulseAudio" to "yes",
            "ServerAliveInterval" to "30",
        ))
        assertFalse("Haven* directive leaked", target.store.keys.any { it.lowercase().startsWith("haven") })
        assertFalse("ForwardX11 leaked", target.store.keys.any { it.lowercase().startsWith("forward") })
        // ServerAliveInterval routes to the real setter (seconds -> ms),
        // never to the inert setConfig map (#537).
        assertEquals(30_000, target.aliveIntervalMs)
        assertFalse("ServerAliveInterval leaked into config", "ServerAliveInterval" in target.store)
    }

    // --- Prefix semantics on mergeAlgorithmList ---

    @Test
    fun `merge with no prefix replaces the list`() {
        val merged = SshOptionsApplier.mergeAlgorithmList("a,b,c", "x,y")
        assertEquals("x,y", merged)
    }

    @Test
    fun `merge with + appends and dedupes`() {
        assertEquals("a,b,c,d", SshOptionsApplier.mergeAlgorithmList("a,b,c", "+c,d"))
    }

    @Test
    fun `merge with ^ prepends and dedupes`() {
        assertEquals("d,c,a,b", SshOptionsApplier.mergeAlgorithmList("a,b,c", "^d,c"))
    }

    @Test
    fun `merge with minus removes literal entries`() {
        assertEquals("a,c", SshOptionsApplier.mergeAlgorithmList("a,b,c", "-b"))
    }

    @Test
    fun `merge with minus tolerates absent entries`() {
        assertEquals("a,b,c", SshOptionsApplier.mergeAlgorithmList("a,b,c", "-nope"))
    }

    @Test
    fun `merge with minus supports star glob`() {
        val base = "diffie-hellman-group14-sha1,diffie-hellman-group14-sha256,diffie-hellman-group1-sha1,curve25519-sha256"
        val merged = SshOptionsApplier.mergeAlgorithmList(base, "-diffie-hellman-group1-*")
        assertEquals("diffie-hellman-group14-sha1,diffie-hellman-group14-sha256,curve25519-sha256", merged)
    }

    @Test
    fun `merge with empty user value returns base`() {
        assertEquals("a,b,c", SshOptionsApplier.mergeAlgorithmList("a,b,c", ""))
        assertEquals("a,b,c", SshOptionsApplier.mergeAlgorithmList("a,b,c", "+"))
    }

    @Test
    fun `merge tolerates whitespace around comma-separated items`() {
        assertEquals("a,b,c,d", SshOptionsApplier.mergeAlgorithmList("a, b , c", "+ d "))
    }

    // --- Fan-out: one OpenSSH key, two JSch keys ---

    @Test
    fun `Ciphers fans out to cipher c2s and s2c`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("Ciphers" to "+aes128-cbc"))
        assertTrue("aes128-cbc in c2s", "aes128-cbc" in target.store["cipher.c2s"]!!.split(","))
        assertTrue("aes128-cbc in s2c", "aes128-cbc" in target.store["cipher.s2c"]!!.split(","))
    }

    @Test
    fun `MACs minus removes from both directions`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("MACs" to "-hmac-sha1"))
        assertFalse("hmac-sha1 removed c2s", "hmac-sha1" in target.store["mac.c2s"]!!.split(","))
        assertFalse("hmac-sha1 removed s2c", "hmac-sha1" in target.store["mac.s2c"]!!.split(","))
    }

    @Test
    fun `HostKeyAlgorithms maps to server_host_key`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("HostKeyAlgorithms" to "^ssh-rsa,ssh-dss"))
        val v = target.store["server_host_key"]!!
        assertTrue("ssh-rsa prepended: $v", v.startsWith("ssh-rsa,ssh-dss,"))
    }

    @Test
    fun `PubkeyAcceptedKeyTypes legacy alias targets PubkeyAcceptedAlgorithms`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("PubkeyAcceptedKeyTypes" to "+ssh-rsa"))
        assertTrue("ssh-rsa appended", "ssh-rsa" in target.store["PubkeyAcceptedAlgorithms"]!!.split(","))
        assertFalse("alias key not leaked", "PubkeyAcceptedKeyTypes" in target.store)
    }

    // --- PubkeyAuthentication translation ---

    @Test
    fun `PubkeyAuthentication no removes publickey from PreferredAuthentications`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("PubkeyAuthentication" to "no"))
        val pref = target.store["PreferredAuthentications"]!!
        assertFalse("publickey removed: $pref", "publickey" in pref.split(","))
        assertTrue("password kept: $pref", "password" in pref.split(","))
    }

    @Test
    fun `PubkeyAuthentication yes is no-op when publickey already present`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("PubkeyAuthentication" to "yes"))
        assertEquals("publickey,keyboard-interactive,password", target.store["PreferredAuthentications"])
    }

    @Test
    fun `PubkeyAuthentication yes re-adds publickey if missing`() {
        val target = FakeTarget(mapOf("PreferredAuthentications" to "keyboard-interactive,password"))
        SshOptionsApplier.apply(target, mapOf("PubkeyAuthentication" to "yes"))
        assertEquals("publickey,keyboard-interactive,password", target.store["PreferredAuthentications"])
    }

    // --- Reserved keys + pass-through + case-insensitivity ---

    @Test
    fun `StrictHostKeyChecking override is ignored (Haven owns TOFU)`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("StrictHostKeyChecking" to "yes"))
        assertEquals("no", target.store["StrictHostKeyChecking"])
    }

    @Test
    fun `unknown key passes through verbatim for forward compat`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("RekeyLimit" to "1G"))
        assertEquals("1G", target.store["RekeyLimit"])
    }

    // --- ServerAlive overrides: JSch ignores these in the config map, so
    // --- they must reach the Session setters (#537, found by kanazawahere).

    @Test
    fun `ServerAliveInterval seconds reach the setter as milliseconds`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("ServerAliveInterval" to "60"))
        assertEquals(60_000, target.aliveIntervalMs)
        assertFalse("leaked into config", "ServerAliveInterval" in target.store)
    }

    @Test
    fun `ServerAliveInterval zero disables keepalive via the setter`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("ServerAliveInterval" to "0"))
        assertEquals(0, target.aliveIntervalMs)
    }

    @Test
    fun `huge ServerAliveInterval clamps cleanly instead of overflowing Int milliseconds (#895)`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("ServerAliveInterval" to "9999999"))
        assertEquals(86_400_000, target.aliveIntervalMs)
    }

    @Test
    fun `ServerAliveCountMax routes to the setter`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("serveralivecountmax" to "5"))
        assertEquals(5, target.aliveCountMax)
        assertFalse("leaked into config", target.store.keys.any { it.lowercase() == "serveralivecountmax" })
    }

    @Test
    fun `unparsable or negative ServerAlive values leave the default untouched`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf(
            "ServerAliveInterval" to "-5",
            "ServerAliveCountMax" to "soon",
        ))
        assertEquals(null, target.aliveIntervalMs)
        assertEquals(null, target.aliveCountMax)
    }

    @Test
    fun `key matching is case-insensitive`() {
        val target = defaultTarget()
        SshOptionsApplier.apply(target, mapOf("KEXALGORITHMS" to "+diffie-hellman-group14-sha1"))
        assertTrue(
            "group14-sha1 appended via uppercase key",
            "diffie-hellman-group14-sha1" in target.store["kex"]!!.split(","),
        )
    }

    @Test
    fun `blank value is a no-op`() {
        val target = defaultTarget()
        val originalKex = target.store["kex"]!!
        SshOptionsApplier.apply(target, mapOf("KexAlgorithms" to "   "))
        assertEquals(originalKex, target.store["kex"])
    }
}
