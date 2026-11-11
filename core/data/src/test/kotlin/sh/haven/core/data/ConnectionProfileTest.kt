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
    fun `openaiBaseUrl handles full https URLs, prefixes, and bare hosts`() {
        assertEquals(
            "https://openrouter.ai/api/v1",
            ConnectionProfile(
                label = "or",
                host = "https://openrouter.ai/api/v1/",
                port = 0,
                username = "",
                connectionType = "OPENAI",
            ).openaiBaseUrl,
        )
        assertEquals(
            "https://openrouter.ai/api",
            ConnectionProfile(
                label = "or",
                host = "https://openrouter.ai",
                port = 0,
                username = "",
                connectionType = "OPENAI",
                openaiPathPrefix = "/api",
            ).openaiBaseUrl,
        )
        assertEquals(
            "https://openrouter.ai",
            ConnectionProfile(
                label = "or",
                host = "openrouter.ai",
                port = 443,
                username = "",
                connectionType = "OPENAI",
            ).openaiBaseUrl,
        )
        assertEquals(
            "http://192.168.1.20:80",
            ConnectionProfile(
                label = "local",
                host = "192.168.1.20",
                port = 0,
                username = "",
                connectionType = "OPENAI",
            ).openaiBaseUrl,
        )
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

    @Test
    fun `copy preserves ephemeral marker`() {
        val ephemeral = ConnectionProfile(label = "quick", host = "10.0.0.5", username = "root")
            .also { it.ephemeral = true }
        val copied = ephemeral.copy(host = "10.0.0.6", sshPassword = "ENC:x")
        assertEquals(true, copied.ephemeral)

        val persisted = ConnectionProfile(label = "saved", host = "10.0.0.5", username = "root")
        assertEquals(false, persisted.copy(host = "10.0.0.6").ephemeral)
    }

    @Test
    fun `CREDENTIAL_FIELDS covers every secret-named field on ConnectionProfile`() {
        val secretPattern = Regex("(?i).*(password|passphrase|secret|apikey|token|spakey|spahmackey).*")
        val nonSecretAllowlist = setOf(
            // Boolean encoding toggles for how spaKey/spaHmacKey are interpreted, not secrets.
            "spaKeyBase64",
            "spaHmacKeyBase64",
        )
        val reflectedSecretFields = ConnectionProfile::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filter { secretPattern.matches(it) && it !in nonSecretAllowlist }
            .toSet()

        val registered = ConnectionProfile.CREDENTIAL_FIELDS.map { it.name }.toSet()
        assertEquals(
            "Every secret-looking ConnectionProfile column must be in CREDENTIAL_FIELDS (or nonSecretAllowlist)",
            reflectedSecretFields,
            registered,
        )
        assertEquals(13, ConnectionProfile.CREDENTIAL_FIELDS.size)

        // Verify each descriptor reads and writes its target column without mutating neighbors.
        val base = ConnectionProfile(label = "t", host = "h", username = "u")
        for (field in ConnectionProfile.CREDENTIAL_FIELDS) {
            assertNull(field.read(base))
            val populated = field.write(base, "val-${field.name}")
            assertEquals("val-${field.name}", field.read(populated))
            for (other in ConnectionProfile.CREDENTIAL_FIELDS) {
                if (other.name != field.name) {
                    assertNull("Writing ${field.name} mutated ${other.name}", other.read(populated))
                }
            }
        }
    }

    @Test
    fun `Room persistence constructor matches primary constructor minus ephemeral`() {
        // Note: @androidx.room.Ignore has BINARY retention (enforced by Room KSP at compile time),
        // so at runtime we distinguish the two explicit constructors by arity.
        val constructors = ConnectionProfile::class.java.declaredConstructors
            .filter { !it.isSynthetic && it.parameterCount > 0 }
            .sortedBy { it.parameterCount }
        assertEquals(2, constructors.size)
        val (roomCtor, primaryCtor) = constructors
        assertEquals(primaryCtor.parameterCount - 1, roomCtor.parameterCount)
        assertEquals(
            primaryCtor.parameterTypes.dropLast(1),
            roomCtor.parameterTypes.toList(),
        )
        assertEquals(Boolean::class.javaPrimitiveType, primaryCtor.parameterTypes.last())
    }
}
