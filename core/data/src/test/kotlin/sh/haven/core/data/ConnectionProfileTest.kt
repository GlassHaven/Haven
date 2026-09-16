package sh.haven.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

class ConnectionProfileTest {

    @Test
    fun `default port is 22`() {
        val profile = ConnectionProfile(
            label = "test",
            host = "example.com",
            username = "user",
        )
        assertEquals(22, profile.port)
    }

    @Test
    fun `default auth type is PASSWORD`() {
        val profile = ConnectionProfile(
            label = "test",
            host = "example.com",
            username = "user",
        )
        assertEquals(ConnectionProfile.AuthType.PASSWORD, profile.authType)
    }

    @Test
    fun `id is auto-generated UUID`() {
        val p1 = ConnectionProfile(label = "a", host = "h", username = "u")
        val p2 = ConnectionProfile(label = "a", host = "h", username = "u")
        assertNotEquals("Each profile should get a unique ID", p1.id, p2.id)
    }

    @Test
    fun `keyId is null by default`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(profile.keyId)
    }

    @Test
    fun `lastConnected is null by default`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(profile.lastConnected)
    }

    @Test
    fun `remote command defaults to an interactive PTY-backed exec when configured`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(profile.remoteCommand)
        assertEquals(true, profile.requestPty)
    }

    @Test
    fun `copy preserves id`() {
        val original = ConnectionProfile(label = "a", host = "h", username = "u")
        val copy = original.copy(label = "b")
        assertEquals(original.id, copy.id)
        assertEquals("b", copy.label)
    }

    @Test
    fun `AuthType KEY is distinct from PASSWORD`() {
        assertNotEquals(ConnectionProfile.AuthType.PASSWORD, ConnectionProfile.AuthType.KEY)
    }

    // ---- connectionType computed properties ----

    @Test
    fun `isRdp returns true when connectionType is RDP`() {
        val profile = ConnectionProfile(
            label = "rdp",
            host = "10.0.0.1",
            username = "user",
            connectionType = "RDP",
        )
        assert(profile.isRdp) { "Expected isRdp == true for connectionType=RDP" }
    }

    @Test
    fun `isRdp returns false for SSH connection`() {
        val profile = ConnectionProfile(label = "ssh", host = "h", username = "u")
        assert(!profile.isRdp) { "Expected isRdp == false for default SSH connection" }
    }

    @Test
    fun `isDesktop returns true for VNC connection`() {
        val profile = ConnectionProfile(
            label = "vnc",
            host = "10.0.0.1",
            username = "user",
            connectionType = "VNC",
        )
        assert(profile.isDesktop) { "Expected isDesktop == true for connectionType=VNC" }
    }

    @Test
    fun `isDesktop returns true for RDP connection`() {
        val profile = ConnectionProfile(
            label = "rdp",
            host = "10.0.0.1",
            username = "user",
            connectionType = "RDP",
        )
        assert(profile.isDesktop) { "Expected isDesktop == true for connectionType=RDP" }
    }

    @Test
    fun `isDesktop returns false for SSH connection`() {
        val profile = ConnectionProfile(label = "ssh", host = "h", username = "u")
        assert(!profile.isDesktop) { "Expected isDesktop == false for default SSH connection" }
    }

    // ---- RDP field defaults ----

    @Test
    fun `rdpSshForward defaults to false`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertEquals(false, profile.rdpSshForward)
    }

    @Test
    fun `rdpPassword defaults to null`() {
        val profile = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(profile.rdpPassword)
    }

    // --- #166 multi-factor auth methods ---

    @Test
    fun `authMethodSpecs falls back to PASSWORD when authMethods blank and authType PASSWORD`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u")
        assertEquals(listOf(ConnectionProfile.AuthMethodSpec.Password), p.authMethodSpecs)
    }

    @Test
    fun `authMethodSpecs falls back to Key with keyId when authType KEY`() {
        val p = ConnectionProfile(
            label = "t", host = "h", username = "u",
            authType = ConnectionProfile.AuthType.KEY, keyId = "k1",
        )
        assertEquals(listOf(ConnectionProfile.AuthMethodSpec.Key("k1")), p.authMethodSpecs)
    }

    @Test
    fun `authMethodSpecs parses an ordered key+password list`() {
        val p = ConnectionProfile(
            label = "t", host = "h", username = "u",
            authMethods = "KEY:k1\nPASSWORD",
        )
        assertEquals(
            listOf(
                ConnectionProfile.AuthMethodSpec.Key("k1"),
                ConnectionProfile.AuthMethodSpec.Password,
            ),
            p.authMethodSpecs,
        )
    }

    @Test
    fun `AuthMethodSpec round-trips through serialize and parse`() {
        val specs = listOf(
            ConnectionProfile.AuthMethodSpec.Key("abc"),
            ConnectionProfile.AuthMethodSpec.Password,
            ConnectionProfile.AuthMethodSpec.KeyboardInteractive,
            ConnectionProfile.AuthMethodSpec.Key(null),
            ConnectionProfile.AuthMethodSpec.Totp("sec1"),
            ConnectionProfile.AuthMethodSpec.Totp(null),
        )
        val text = ConnectionProfile.AuthMethodSpec.serializeList(specs)
        assertEquals(specs, ConnectionProfile.AuthMethodSpec.parseList(text))
    }

    @Test
    fun `AuthMethodSpec parses TOTP tokens with and without secret id`() {
        assertEquals(
            listOf(
                ConnectionProfile.AuthMethodSpec.Key("k1"),
                ConnectionProfile.AuthMethodSpec.Totp("sec1"),
            ),
            ConnectionProfile.AuthMethodSpec.parseList("KEY:k1\nTOTP:sec1"),
        )
        assertEquals("TOTP", ConnectionProfile.AuthMethodSpec.Totp(null).serialize())
        assertEquals("TOTP:x", ConnectionProfile.AuthMethodSpec.Totp("x").serialize())
    }

    // --- #EMAIL connection type ---

    @Test
    fun `isEmail returns true when connectionType is EMAIL`() {
        val p = ConnectionProfile(
            label = "mail", host = "mail.proton.me", username = "u",
            connectionType = "EMAIL",
        )
        assert(p.isEmail) { "Expected isEmail == true for connectionType=EMAIL" }
    }

    @Test
    fun `EMAIL is not a terminal connection`() {
        val p = ConnectionProfile(
            label = "mail", host = "h", username = "u", connectionType = "EMAIL",
        )
        assert(!p.isTerminal) { "Expected isTerminal == false for EMAIL" }
        assert(!p.isDesktop) { "Expected isDesktop == false for EMAIL" }
    }

    @Test
    fun `EMAIL field defaults`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(p.emailProvider)
        assertEquals(993, p.emailPort)
        assertEquals(465, p.emailSmtpPort)
        assertEquals(true, p.emailTls)
        assertEquals("", p.emailAuthMethods)
        assertNull(p.emailPassword)
        assertNull(p.emailMailboxPassword)
    }

    // --- OPENAI connection type ---

    @Test
    fun `isOpenai returns true when connectionType is OPENAI`() {
        val p = ConnectionProfile(
            label = "llama", host = "msi-z790", username = "u",
            connectionType = "OPENAI",
        )
        assert(p.isOpenai) { "Expected isOpenai == true for connectionType=OPENAI" }
        assert(!p.isEmail) { "OPENAI must not be confused with EMAIL" }
    }

    @Test
    fun `OPENAI is not a terminal or desktop connection`() {
        val p = ConnectionProfile(
            label = "llama", host = "h", username = "u", connectionType = "OPENAI",
        )
        assert(!p.isTerminal) { "Expected isTerminal == false for OPENAI" }
        assert(!p.isDesktop) { "Expected isDesktop == false for OPENAI" }
    }

    @Test
    fun `OPENAI field defaults`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u")
        assertNull(p.openaiPathPrefix)
        assertNull(p.openaiApiKey)
        assertNull(p.aiProtocol)
        assertFalse(p.isOpenai)
    }

    @Test
    fun `aiProtocol is stored as the raw string`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u", aiProtocol = "ANTHROPIC")
        assertEquals("ANTHROPIC", p.aiProtocol)
        // No entity-level normalisation — null/blank stays as written and is
        // treated as OPENAI at the AiProtocol.fromStored boundary.
        assertNull(ConnectionProfile(label = "t", host = "h", username = "u").aiProtocol)
    }

    @Test
    fun `AuthMethodSpec round-trips PROTON_SRP`() {
        assertEquals("PROTON_SRP", ConnectionProfile.AuthMethodSpec.ProtonSrp.serialize())
        assertEquals(
            listOf(
                ConnectionProfile.AuthMethodSpec.ProtonSrp,
                ConnectionProfile.AuthMethodSpec.Totp("sec1"),
            ),
            ConnectionProfile.AuthMethodSpec.parseList("PROTON_SRP\nTOTP:sec1"),
        )
    }

    @Test
    fun `totpConfirmBeforeSend defaults to false`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u")
        assertEquals(false, p.totpConfirmBeforeSend)
    }

    @Test
    fun `ignoreSavedKeys defaults to false`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u")
        assertEquals(false, p.ignoreSavedKeys)
    }

    @Test
    fun `ignoreSavedKeys round-trips through copy`() {
        val p = ConnectionProfile(label = "t", host = "h", username = "u")
        assertEquals(true, p.copy(ignoreSavedKeys = true).ignoreSavedKeys)
    }

    @Test
    fun `AuthMethodSpec parseList ignores blank and unknown tokens`() {
        val parsed = ConnectionProfile.AuthMethodSpec.parseList("PASSWORD\n\nBOGUS\nKEY:x")
        assertEquals(
            listOf(
                ConnectionProfile.AuthMethodSpec.Password,
                ConnectionProfile.AuthMethodSpec.Key("x"),
            ),
            parsed,
        )
    }
}
