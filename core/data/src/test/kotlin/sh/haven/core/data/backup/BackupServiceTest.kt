package sh.haven.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import sh.haven.core.data.preferences.UserPreferencesRepository
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.haven.core.data.db.ConnectionDao
import sh.haven.core.data.db.KnownHostDao
import sh.haven.core.data.db.PortForwardRuleDao
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.PortForwardRule
import sh.haven.core.data.db.entities.SshKey
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.repository.TunnelConfigRepository
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class BackupServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var connectionDao: ConnectionDao
    private lateinit var connectionRepository: sh.haven.core.data.repository.ConnectionRepository
    private lateinit var connectionGroupDao: sh.haven.core.data.db.ConnectionGroupDao
    private lateinit var sshKeyDao: SshKeyDao
    private lateinit var sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository
    private lateinit var knownHostDao: KnownHostDao
    private lateinit var portForwardRuleDao: PortForwardRuleDao
    private lateinit var tunnelConfigRepository: TunnelConfigRepository
    private lateinit var sshIdentityRepository: sh.haven.core.data.repository.SshIdentityRepository
    private lateinit var totpSecretRepository: sh.haven.core.data.repository.TotpSecretRepository
    private lateinit var chatRepository: sh.haven.core.data.repository.ChatRepository
    private lateinit var ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository
    private lateinit var workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository
    private lateinit var syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository
    private lateinit var stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository
    private lateinit var knownTlsCertDao: sh.haven.core.data.db.KnownTlsCertDao
    private lateinit var mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var service: BackupService

    @Before
    fun setup() {
        connectionDao = mockk(relaxed = true)
        connectionRepository = mockk(relaxed = true)
        connectionGroupDao = mockk(relaxed = true)
        sshKeyDao = mockk(relaxed = true)
        sshKeyRepository = mockk(relaxed = true)
        knownHostDao = mockk(relaxed = true)
        portForwardRuleDao = mockk(relaxed = true)
        tunnelConfigRepository = mockk(relaxed = true)
        sshIdentityRepository = mockk(relaxed = true)
        totpSecretRepository = mockk(relaxed = true)
        chatRepository = mockk(relaxed = true)
        ageIdentityRepository = mockk(relaxed = true)
        workspaceRepository = mockk(relaxed = true)
        syncProfileRepository = mockk(relaxed = true)
        stepCaConfigRepository = mockk(relaxed = true)
        knownTlsCertDao = mockk(relaxed = true)
        mailRuleRepository = mockk(relaxed = true)
        // Default: empty collections unless overridden by a test.
        coEvery { tunnelConfigRepository.getAllDecrypted() } returns emptyList()
        coEvery { sshIdentityRepository.getAllDecrypted() } returns emptyList()
        coEvery { chatRepository.getAllConversations() } returns emptyList()
        coEvery { ageIdentityRepository.getAllDecrypted() } returns emptyList()
        coEvery { workspaceRepository.getAllWithItems() } returns emptyList()
        coEvery { syncProfileRepository.getAll() } returns emptyList()
        coEvery { stepCaConfigRepository.getAll() } returns emptyList()
        coEvery { knownTlsCertDao.getAll() } returns emptyList()
        coEvery { mailRuleRepository.getAllRules() } returns emptyList()

        val prefsFile = File(tempFolder.root, "test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { prefsFile }

        service = BackupService(
            connectionDao,
            connectionRepository,
            connectionGroupDao,
            sshKeyDao,
            sshKeyRepository,
            knownHostDao,
            portForwardRuleDao,
            tunnelConfigRepository,
            sshIdentityRepository,
            totpSecretRepository,
            dataStore,
            chatRepository,
            ageIdentityRepository,
            workspaceRepository,
            syncProfileRepository,
            stepCaConfigRepository,
            knownTlsCertDao,
            mailRuleRepository,
        )
    }

    // -- Encrypt/Decrypt roundtrip --

    @Test
    fun `export and import roundtrip with empty data`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("testPassword123")
        val result = service.import(encrypted, "testPassword123")

        assertEquals(0, result.count)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `float preferences survive export-import as Float, not Int (#323, #779)`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        // Fractional and whole-valued floats across all known FLOAT_PREF_KEYS, plus
        // a small-valued Long pref (JSON collapses 1.0 -> 1 and 0L -> 0, so import
        // sees java.lang.Integer).
        val fracKey = floatPreferencesKey("terminal_background_opacity")
        val wholeScaleKey = floatPreferencesKey("app_window_default_scale")
        val toolbarScaleKey = floatPreferencesKey("toolbar_key_scale")
        val touchpadKey = floatPreferencesKey("desktop_touchpad_sensitivity")
        val mailScaleKey = floatPreferencesKey("mail_font_scale")
        val updateRunKey = longPreferencesKey("update_check_last_run_ms")
        dataStore.edit {
            it[fracKey] = 0.85f
            it[wholeScaleKey] = 1.0f
            it[toolbarScaleKey] = 2.0f
            it[touchpadKey] = 1.0f
            it[mailScaleKey] = 2.0f
            it[updateRunKey] = 0L
        }

        val encrypted = service.export("pw")
        // Wipe, then import back.
        dataStore.edit { it.clear() }
        service.import(encrypted, "pw")

        // Every Float key must read back directly as Float (no ClassCastException)
        // and every Long key must read back directly as Long.
        val prefs = dataStore.data.first()
        assertEquals(0.85f, prefs[fracKey])
        assertEquals(1.0f, prefs[wholeScaleKey])
        assertEquals(2.0f, prefs[toolbarScaleKey])
        assertEquals(1.0f, prefs[touchpadKey])
        assertEquals(2.0f, prefs[mailScaleKey])
        assertEquals(0L, prefs[updateRunKey])
    }

    @Test
    fun `UserPreferencesRepository heals already-corrupted Int entries for Float and Long keys (#779)`() = runTest {
        // Simulate a DataStore already corrupted by a pre-#779 backup restore where
        // whole-numbered floats and small longs were written via intPreferencesKey.
        dataStore.edit {
            it[intPreferencesKey("toolbar_key_scale")] = 2
            it[intPreferencesKey("desktop_touchpad_sensitivity")] = 3
            it[intPreferencesKey("mail_font_scale")] = 2
            it[intPreferencesKey("app_window_default_scale")] = 2
            it[intPreferencesKey("terminal_background_opacity")] = 1
            it[intPreferencesKey("update_check_last_run_ms")] = 12345
            it[intPreferencesKey("last_viewed_agent_audit_timestamp")] = 67890
        }

        val repo = UserPreferencesRepository(mockk(relaxed = true), dataStore)
        assertEquals(2.0f, repo.toolbarKeyScale.first())
        assertEquals(3.0f, repo.desktopTouchpadSensitivity.first())
        assertEquals(2.0f, repo.mailFontScale.first())
        assertEquals(2.0f, repo.appWindowDefaultScale.first())
        assertEquals(1.0f, repo.terminalBackgroundOpacity.first())
        assertEquals(12345L, repo.updateCheckLastRunMs.first())
        assertEquals(67890L, repo.lastViewedAgentAuditTimestamp.first())
    }

    @Test(expected = Exception::class)
    fun `import with wrong password throws`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("correctPassword")
        service.import(encrypted, "wrongPassword")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import garbage data throws`() = runTest {
        service.import("not a backup file".toByteArray(), "password")
    }

    @Test
    fun `legacy V1 backup (100k iterations) still imports`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        // Produce a current (V2/600k) backup, recover its plaintext, then
        // re-wrap it as an old V1/100k file and confirm import still reads it.
        val v2 = service.export("pw")
        val plaintext = decryptRaw(v2, "pw", "HAVEN_BACKUP_V2", 600_000)
        val legacy = encryptLegacy(plaintext, "pw")

        val result = service.import(legacy, "pw")
        assertTrue(result.errors.isEmpty())
    }

    private fun deriveKeyForTest(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    private fun decryptRaw(data: ByteArray, password: String, magic: String, iterations: Int): ByteArray {
        var off = magic.length
        val salt = data.copyOfRange(off, off + 16); off += 16
        val iv = data.copyOfRange(off, off + 12); off += 12
        val ct = data.copyOfRange(off, data.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, deriveKeyForTest(password, salt, iterations), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }

    private fun encryptLegacy(plaintext: ByteArray, password: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deriveKeyForTest(password, salt, 100_000), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plaintext)
        return "HAVEN_BACKUP_V1".toByteArray(Charsets.US_ASCII) + salt + iv + ct
    }

    // -- Connection profile roundtrip --

    @Test
    fun `connection profile fields survive export-import roundtrip`() = runTest {
        val profile = ConnectionProfile(
            id = "conn-1",
            label = "My Server",
            host = "192.168.1.100",
            port = 2222,
            username = "admin",
            authType = ConnectionProfile.AuthType.KEY,
            keyId = "key-1",
            colorTag = 3,
            lastConnected = 1700000000000L,
            sortOrder = 5,
            connectionType = "SSH",
            destinationHash = "abc123",
            reticulumHost = "10.0.0.1",
            reticulumPort = 4242,
            jumpProfileId = "jump-1",
            sshOptions = "-o StrictHostKeyChecking=no",
            vncPort = 5901,
            vncPassword = "vncpass",
            vncSshForward = false,
            authMethods = "PASSWORD\nKEY:key-1",
            glkvmPort = 443,
            glkvmPassword = "glkvmpass",
            glkvmTargetLayout = "uk",
            glkvmSshForward = true,
            glkvmSshProfileId = "ssh-tunnel-1",
            emailProvider = "imap",
            emailServer = "imap.gmail.com",
            aiRouteType = "SSH",
            aiRouteProfileId = "ssh-tunnel-1",
        )

        coEvery { connectionRepository.getAll() } returns listOf(profile)
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")

        // Capture the upserted profile on import
        val captured = slot<ConnectionProfile>()
        coEvery { connectionRepository.save(capture(captured)) } returns Unit

        val result = service.import(encrypted, "pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())

        val imported = captured.captured
        assertEquals("conn-1", imported.id)
        assertEquals("My Server", imported.label)
        assertEquals("192.168.1.100", imported.host)
        assertEquals(2222, imported.port)
        assertEquals("admin", imported.username)
        assertEquals(ConnectionProfile.AuthType.KEY, imported.authType)
        assertEquals("key-1", imported.keyId)
        assertEquals(3, imported.colorTag)
        assertEquals(1700000000000L, imported.lastConnected)
        assertEquals(5, imported.sortOrder)
        assertEquals("SSH", imported.connectionType)
        assertEquals("abc123", imported.destinationHash)
        assertEquals("10.0.0.1", imported.reticulumHost)
        assertEquals(4242, imported.reticulumPort)
        assertEquals("jump-1", imported.jumpProfileId)
        assertEquals("-o StrictHostKeyChecking=no", imported.sshOptions)
        assertEquals(5901, imported.vncPort)
        assertEquals("vncpass", imported.vncPassword)
        assertEquals(false, imported.vncSshForward)
        assertEquals("PASSWORD\nKEY:key-1", imported.authMethods)
        assertEquals(443, imported.glkvmPort)
        assertEquals("glkvmpass", imported.glkvmPassword)
        assertEquals("uk", imported.glkvmTargetLayout)
        assertEquals(true, imported.glkvmSshForward)
        assertEquals("ssh-tunnel-1", imported.glkvmSshProfileId)
        assertEquals("imap", imported.emailProvider)
        assertEquals("imap.gmail.com", imported.emailServer)
        assertEquals("SSH", imported.aiRouteType)
        assertEquals("ssh-tunnel-1", imported.aiRouteProfileId)
    }

    @Test
    fun `connection profile with null optional fields`() = runTest {
        val profile = ConnectionProfile(
            id = "conn-2",
            label = "Minimal",
            host = "example.com",
            username = "user",
        )

        coEvery { connectionRepository.getAll() } returns listOf(profile)
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")

        val captured = slot<ConnectionProfile>()
        coEvery { connectionRepository.save(capture(captured)) } returns Unit

        service.import(encrypted, "pw")

        val imported = captured.captured
        assertNull(imported.keyId)
        assertNull(imported.lastConnected)
        assertNull(imported.destinationHash)
        assertNull(imported.jumpProfileId)
        assertNull(imported.sshOptions)
        assertNull(imported.vncPort)
        assertNull(imported.vncPassword)
        assertEquals(true, imported.vncSshForward) // default
    }

    // -- SSH key roundtrip --

    @Test
    fun `ssh key fields survive export-import roundtrip`() = runTest {
        val key = SshKey(
            id = "key-1",
            label = "My Key",
            keyType = "ED25519",
            privateKeyBytes = byteArrayOf(1, 2, 3, 4, 5, 0, -1, -128, 127),
            publicKeyOpenSsh = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest",
            fingerprintSha256 = "SHA256:abcdef123456",
            createdAt = 1700000000000L,
        )

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns listOf(key)
        coEvery { sshKeyRepository.getAllDecrypted() } returns listOf(key)
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")

        val captured = slot<SshKey>()
        coEvery { sshKeyRepository.save(capture(captured)) } returns Unit

        service.import(encrypted, "pw")

        val imported = captured.captured
        assertEquals("key-1", imported.id)
        assertEquals("My Key", imported.label)
        assertEquals("ED25519", imported.keyType)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 0, -1, -128, 127), imported.privateKeyBytes)
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest", imported.publicKeyOpenSsh)
        assertEquals("SHA256:abcdef123456", imported.fingerprintSha256)
        assertEquals(1700000000000L, imported.createdAt)
    }

    // -- Known host roundtrip --

    @Test
    fun `known host fields survive export-import roundtrip`() = runTest {
        val host = KnownHost(
            id = 0, // auto-generated, not serialized
            hostname = "example.com",
            port = 22,
            keyType = "ssh-rsa",
            publicKeyBase64 = "AAAAB3NzaC1yc2EAAAADAQAB",
            fingerprint = "SHA256:xyzzy",
            firstSeen = 1700000000000L,
        )

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns listOf(host)
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")

        val captured = slot<KnownHost>()
        coEvery { knownHostDao.upsert(capture(captured)) } returns Unit

        service.import(encrypted, "pw")

        val imported = captured.captured
        assertEquals("example.com", imported.hostname)
        assertEquals(22, imported.port)
        assertEquals("ssh-rsa", imported.keyType)
        assertEquals("AAAAB3NzaC1yc2EAAAADAQAB", imported.publicKeyBase64)
        assertEquals("SHA256:xyzzy", imported.fingerprint)
        assertEquals(1700000000000L, imported.firstSeen)
    }

    // -- Port forward rule roundtrip --

    @Test
    fun `port forward rule fields survive export-import roundtrip`() = runTest {
        val rule = PortForwardRule(
            id = "pf-1",
            profileId = "conn-1",
            type = PortForwardRule.Type.LOCAL,
            bindAddress = "127.0.0.1",
            bindPort = 8080,
            targetHost = "db.internal",
            targetPort = 3306,
            enabled = true,
        )

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns listOf(rule)

        val encrypted = service.export("pw")

        val captured = slot<PortForwardRule>()
        coEvery { portForwardRuleDao.upsert(capture(captured)) } returns Unit

        service.import(encrypted, "pw")

        val imported = captured.captured
        assertEquals("pf-1", imported.id)
        assertEquals("conn-1", imported.profileId)
        assertEquals(PortForwardRule.Type.LOCAL, imported.type)
        assertEquals("127.0.0.1", imported.bindAddress)
        assertEquals(8080, imported.bindPort)
        assertEquals("db.internal", imported.targetHost)
        assertEquals(3306, imported.targetPort)
        assertEquals(true, imported.enabled)
    }

    @Test
    fun `remote port forward type roundtrips`() = runTest {
        val rule = PortForwardRule(
            id = "pf-2",
            profileId = "conn-1",
            type = PortForwardRule.Type.REMOTE,
            bindAddress = "0.0.0.0",
            bindPort = 2222,
            targetHost = "localhost",
            targetPort = 22,
            enabled = false,
        )

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns listOf(rule)

        val encrypted = service.export("pw")

        val captured = slot<PortForwardRule>()
        coEvery { portForwardRuleDao.upsert(capture(captured)) } returns Unit

        service.import(encrypted, "pw")

        val imported = captured.captured
        assertEquals(PortForwardRule.Type.REMOTE, imported.type)
        assertEquals(false, imported.enabled)
    }

    // -- Settings roundtrip --

    @Test
    fun `settings survive export-import roundtrip`() = runTest {
        // Populate DataStore with test values
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("theme")] = "dark"
            prefs[intPreferencesKey("fontSize")] = 14
            prefs[booleanPreferencesKey("hapticFeedback")] = true
        }

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")

        // Clear datastore before import
        dataStore.edit { it.clear() }

        service.import(encrypted, "pw")

        // Verify settings were restored
        val prefs = dataStore.data.first()
        assertEquals("dark", prefs[stringPreferencesKey("theme")])
        assertEquals(14, prefs[intPreferencesKey("fontSize")])
        assertEquals(true, prefs[booleanPreferencesKey("hapticFeedback")])
    }

    // -- Backward compatibility --

    @Test
    fun `import old backup without vnc fields uses defaults`() = runTest {
        // Simulate a v1 backup JSON without vncPort/vncPassword/vncSshForward
        val json = JSONObject().apply {
            put("version", 1)
            put("created", System.currentTimeMillis())
            put("connections", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "old-conn")
                    put("label", "Old Server")
                    put("host", "10.0.0.1")
                    put("port", 22)
                    put("username", "user")
                    put("authType", "PASSWORD")
                    put("keyId", JSONObject.NULL)
                    put("colorTag", 0)
                    put("lastConnected", JSONObject.NULL)
                    put("sortOrder", 0)
                    put("connectionType", "SSH")
                    put("destinationHash", JSONObject.NULL)
                    put("reticulumHost", "127.0.0.1")
                    put("reticulumPort", 37428)
                    put("jumpProfileId", JSONObject.NULL)
                    put("sshOptions", JSONObject.NULL)
                    // No vncPort, vncPassword, vncSshForward
                })
            })
            put("keys", org.json.JSONArray())
            put("knownHosts", org.json.JSONArray())
            put("portForwards", org.json.JSONArray())
            put("settings", JSONObject())
        }

        // Encrypt the JSON manually using the same format
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        val encrypted = encryptForTest(plaintext, "pw")

        val captured = slot<ConnectionProfile>()
        coEvery { connectionRepository.save(capture(captured)) } returns Unit

        val result = service.import(encrypted, "pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())

        val imported = captured.captured
        assertEquals("old-conn", imported.id)
        assertNull(imported.vncPort)
        assertNull(imported.vncPassword)
        assertEquals(true, imported.vncSshForward) // default
    }

    @Test
    fun `import rejects future version`() = runTest {
        val json = JSONObject().apply {
            put("version", 999)
            put("created", System.currentTimeMillis())
        }
        val encrypted = encryptForTest(json.toString().toByteArray(Charsets.UTF_8), "pw")

        val result = service.import(encrypted, "pw")
        assertEquals(0, result.count)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("newer than supported"))
    }

    // -- Multiple entities --

    @Test
    fun `full backup with all entity types counts correctly`() = runTest {
        val connections = listOf(
            ConnectionProfile(id = "c1", label = "A", host = "h", username = "u"),
            ConnectionProfile(id = "c2", label = "B", host = "h", username = "u"),
        )
        val keys = listOf(
            SshKey(id = "k1", label = "K", keyType = "ED25519",
                privateKeyBytes = byteArrayOf(1, 2, 3),
                publicKeyOpenSsh = "ssh-ed25519 AAA", fingerprintSha256 = "SHA256:x"),
        )
        val hosts = listOf(
            KnownHost(hostname = "a.com", port = 22, keyType = "ssh-rsa",
                publicKeyBase64 = "AAA", fingerprint = "SHA256:y"),
            KnownHost(hostname = "b.com", port = 22, keyType = "ssh-rsa",
                publicKeyBase64 = "BBB", fingerprint = "SHA256:z"),
            KnownHost(hostname = "c.com", port = 2222, keyType = "ssh-ed25519",
                publicKeyBase64 = "CCC", fingerprint = "SHA256:w"),
        )
        val forwards = listOf(
            PortForwardRule(id = "p1", profileId = "c1", type = PortForwardRule.Type.LOCAL,
                bindPort = 8080, targetPort = 80),
        )

        coEvery { connectionRepository.getAll() } returns connections
        coEvery { sshKeyDao.getAll() } returns keys
        coEvery { sshKeyRepository.getAllDecrypted() } returns keys
        coEvery { knownHostDao.getAll() } returns hosts
        coEvery { portForwardRuleDao.getAll() } returns forwards

        val encrypted = service.export("pw")
        val result = service.import(encrypted, "pw")

        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())
        // 1 key + 2 connections + 3 hosts + 1 forward = 7
        assertEquals(7, result.count)

        coVerify(exactly = 2) { connectionRepository.save(any()) }
        coVerify(exactly = 1) { sshKeyRepository.save(any()) }
        coVerify(exactly = 3) { knownHostDao.upsert(any()) }
        coVerify(exactly = 1) { portForwardRuleDao.upsert(any()) }
    }

    // -- v2 additions: tunnels + the 13 previously-dropped connection fields --

    @Test
    fun `tunnel config fields survive export-import roundtrip`() = runTest {
        val tunnel = TunnelConfig(
            id = "tun-1",
            label = "Home WG",
            type = "WIREGUARD",
            configText = "[Interface]\nPrivateKey = abc\n[Peer]\nEndpoint = 1.2.3.4:51820\n".toByteArray(),
            createdAt = 1700000000000L,
        )

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        coEvery { tunnelConfigRepository.getAllDecrypted() } returns listOf(tunnel)

        val encrypted = service.export("pw")

        val captured = slot<TunnelConfig>()
        coEvery { tunnelConfigRepository.save(capture(captured)) } returns Unit

        val result = service.import(encrypted, "pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())

        val imported = captured.captured
        assertEquals("tun-1", imported.id)
        assertEquals("Home WG", imported.label)
        assertEquals("WIREGUARD", imported.type)
        assertArrayEquals(tunnel.configText, imported.configText)
        assertEquals(1700000000000L, imported.createdAt)
    }

    @Test
    fun `connection profile v2 fields survive export-import roundtrip`() = runTest {
        val profile = ConnectionProfile(
            id = "conn-v2",
            label = "Full",
            host = "h",
            username = "u",
            vncUsername = "vncuser",
            lastSessionName = "tmux-work",
            disableAltScreen = true,
            rcloneRemoteName = "drive",
            rcloneProvider = "drive",
            useAndroidShell = true,
            forwardAgent = true,
            reticulumNetworkName = "mynet",
            reticulumPassphrase = "secret",
            postLoginCommand = "tmux attach",
            postLoginBeforeSessionManager = false,
            remoteCommand = "tmux new -A -s work",
            requestPty = false,
            fileTransport = "SFTP",
            tunnelConfigId = "tun-1",
        )

        coEvery { connectionRepository.getAll() } returns listOf(profile)
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")

        val captured = slot<ConnectionProfile>()
        coEvery { connectionRepository.save(capture(captured)) } returns Unit

        val result = service.import(encrypted, "pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())

        val imported = captured.captured
        assertEquals("vncuser", imported.vncUsername)
        assertEquals("tmux-work", imported.lastSessionName)
        assertEquals(true, imported.disableAltScreen)
        assertEquals("drive", imported.rcloneRemoteName)
        assertEquals("drive", imported.rcloneProvider)
        assertEquals(true, imported.useAndroidShell)
        assertEquals(true, imported.forwardAgent)
        assertEquals("mynet", imported.reticulumNetworkName)
        assertEquals("secret", imported.reticulumPassphrase)
        assertEquals("tmux attach", imported.postLoginCommand)
        assertEquals(false, imported.postLoginBeforeSessionManager)
        assertEquals("tmux new -A -s work", imported.remoteCommand)
        assertEquals(false, imported.requestPty)
        assertEquals("SFTP", imported.fileTransport)
        assertEquals("tun-1", imported.tunnelConfigId)
    }

    @Test
    fun `v1 backup without tunnels imports cleanly`() = runTest {
        // Build a v1 JSON envelope with no tunnels key at all — the format
        // before v5.24.9. Import must succeed, no errors, no tunnel saves.
        val json = JSONObject().apply {
            put("version", 1)
            put("created", System.currentTimeMillis())
            put("connections", org.json.JSONArray())
            put("keys", org.json.JSONArray())
            put("knownHosts", org.json.JSONArray())
            put("portForwards", org.json.JSONArray())
            put("settings", JSONObject())
        }
        val encrypted = encryptForTest(json.toString().toByteArray(Charsets.UTF_8), "pw")

        val result = service.import(encrypted, "pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())
        coVerify(exactly = 0) { tunnelConfigRepository.save(any()) }
    }

    /**
     * Helper to encrypt test data using the same format as BackupService.
     * Uses reflection to access the private encrypt method.
     */
    private fun encryptForTest(plaintext: ByteArray, password: String): ByteArray {
        val method = BackupService::class.java.getDeclaredMethod(
            "encrypt", ByteArray::class.java, String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, plaintext, password) as ByteArray
    }

    // -- Cross-device round-trip (#145) --
    //
    // The exporting and importing services share nothing — fresh DAO mocks,
    // fresh DataStore — so anything device-specific in the encryption envelope
    // (e.g. accidental reliance on shared in-memory salts, KeyStore handles,
    // PRNG seeding) shows up as a decrypt failure here. The pre-existing
    // tests above all reuse the same `service` for both sides which makes
    // them blind to this class of bug.

    /**
     * Build a second BackupService backed by entirely separate mocks +
     * DataStore. Mirrors what happens when a user installs Haven on a new
     * device and imports a .enc file from their old device.
     */
    private fun newImporterService(): BackupServicePair {
        val cd = mockk<ConnectionDao>(relaxed = true)
        val cr = mockk<sh.haven.core.data.repository.ConnectionRepository>(relaxed = true)
        val cgd = mockk<sh.haven.core.data.db.ConnectionGroupDao>(relaxed = true)
        val skd = mockk<SshKeyDao>(relaxed = true)
        val skr = mockk<sh.haven.core.data.repository.SshKeyRepository>(relaxed = true)
        val khd = mockk<KnownHostDao>(relaxed = true)
        val pfd = mockk<PortForwardRuleDao>(relaxed = true)
        val tcr = mockk<TunnelConfigRepository>(relaxed = true)
        coEvery { tcr.getAllDecrypted() } returns emptyList()
        val sir = mockk<sh.haven.core.data.repository.SshIdentityRepository>(relaxed = true)
        coEvery { sir.getAllDecrypted() } returns emptyList()
        val air = mockk<sh.haven.core.data.repository.AgeIdentityRepository>(relaxed = true)
        val wr = mockk<sh.haven.core.data.repository.WorkspaceRepository>(relaxed = true)
        val spr = mockk<sh.haven.core.data.repository.SyncProfileRepository>(relaxed = true)
        val scar = mockk<sh.haven.core.data.repository.StepCaConfigRepository>(relaxed = true)
        val ktcd = mockk<sh.haven.core.data.db.KnownTlsCertDao>(relaxed = true)
        val mrr = mockk<sh.haven.core.data.repository.MailRuleRepository>(relaxed = true)
        val ds = PreferenceDataStoreFactory.create {
            File(tempFolder.root, "importer-${System.nanoTime()}.preferences_pb")
        }
        return BackupServicePair(
            service = BackupService(
                cd, cr, cgd, skd, skr, khd, pfd, tcr, sir, mockk(relaxed = true), ds, mockk(relaxed = true),
                air, wr, spr, scar, ktcd, mrr,
            ),
            connectionRepository = cr,
            sshKeyRepository = skr,
            knownHostDao = khd,
            portForwardRuleDao = pfd,
            tunnelConfigRepository = tcr,
            ageIdentityRepository = air,
            workspaceRepository = wr,
            syncProfileRepository = spr,
            stepCaConfigRepository = scar,
            knownTlsCertDao = ktcd,
            mailRuleRepository = mrr,
        )
    }

    private data class BackupServicePair(
        val service: BackupService,
        val connectionRepository: sh.haven.core.data.repository.ConnectionRepository,
        val sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
        val knownHostDao: KnownHostDao,
        val portForwardRuleDao: PortForwardRuleDao,
        val tunnelConfigRepository: TunnelConfigRepository,
        val ageIdentityRepository: sh.haven.core.data.repository.AgeIdentityRepository,
        val workspaceRepository: sh.haven.core.data.repository.WorkspaceRepository,
        val syncProfileRepository: sh.haven.core.data.repository.SyncProfileRepository,
        val stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository,
        val knownTlsCertDao: sh.haven.core.data.db.KnownTlsCertDao,
        val mailRuleRepository: sh.haven.core.data.repository.MailRuleRepository,
    )

    @Test
    fun `cross-device roundtrip preserves all entity types`() = runTest {
        val connections = listOf(
            ConnectionProfile(id = "c1", label = "A", host = "h", username = "u"),
            ConnectionProfile(id = "c2", label = "B", host = "h2", username = "u2",
                authType = ConnectionProfile.AuthType.KEY, keyId = "k1"),
        )
        val keys = listOf(
            SshKey(id = "k1", label = "K", keyType = "ED25519",
                privateKeyBytes = byteArrayOf(1, 2, 3),
                publicKeyOpenSsh = "ssh-ed25519 AAA", fingerprintSha256 = "SHA256:x"),
        )
        val hosts = listOf(
            KnownHost(hostname = "a.com", port = 22, keyType = "ssh-rsa",
                publicKeyBase64 = "AAA", fingerprint = "SHA256:y"),
        )
        val tunnels = listOf(
            TunnelConfig(id = "tun-1", label = "WG", type = "WIREGUARD",
                configText = "[Interface]\n".toByteArray(), createdAt = 1700000000000L),
        )

        // -- Device A (the exporting one): the test fixture's `service`. --
        coEvery { connectionRepository.getAll() } returns connections
        coEvery { sshKeyDao.getAll() } returns keys
        coEvery { sshKeyRepository.getAllDecrypted() } returns keys
        coEvery { knownHostDao.getAll() } returns hosts
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        coEvery { tunnelConfigRepository.getAllDecrypted() } returns tunnels
        val encrypted = service.export("user-password-123")

        // -- Device B (a fresh install): brand-new mocks, brand-new DataStore. --
        val b = newImporterService()
        val savedProfiles = mutableListOf<ConnectionProfile>()
        val savedKeys = mutableListOf<SshKey>()
        val savedHosts = mutableListOf<KnownHost>()
        val savedTunnels = mutableListOf<TunnelConfig>()
        coEvery { b.connectionRepository.save(any()) } answers {
            savedProfiles += firstArg<ConnectionProfile>()
            Unit
        }
        coEvery { b.sshKeyRepository.save(any()) } answers {
            savedKeys += firstArg<SshKey>()
            Unit
        }
        coEvery { b.knownHostDao.upsert(any()) } answers {
            savedHosts += firstArg<KnownHost>()
            Unit
        }
        coEvery { b.tunnelConfigRepository.save(any()) } answers {
            savedTunnels += firstArg<TunnelConfig>()
            Unit
        }

        val result = b.service.import(encrypted, "user-password-123")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())

        // 2 profiles + 1 key + 1 host + 1 tunnel = 5 (settings don't count
        // toward the total — they're a single block, not per-row).
        assertEquals(5, result.count)
        assertEquals(2, savedProfiles.size)
        assertEquals(setOf("c1", "c2"), savedProfiles.map { it.id }.toSet())
        assertEquals(1, savedKeys.size)
        assertEquals("k1", savedKeys.first().id)
        assertArrayEquals(byteArrayOf(1, 2, 3), savedKeys.first().privateKeyBytes)
        assertEquals(1, savedHosts.size)
        assertEquals("a.com", savedHosts.first().hostname)
        assertEquals(1, savedTunnels.size)
        assertEquals("tun-1", savedTunnels.first().id)
    }

    @Test
    fun `cross-device wrong password fails with AEAD tag exception`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        val encrypted = service.export("right")

        val b = newImporterService()
        val thrown = runCatching { b.service.import(encrypted, "wrong") }.exceptionOrNull()
        assertTrue(
            "Expected AEADBadTagException, got ${thrown?.javaClass}",
            thrown is javax.crypto.AEADBadTagException,
        )
    }

    // -- Error-discriminating tests (#145) --
    //
    // Every failure path must throw with a non-null, intelligible message so
    // that SettingsViewModel.importBackup surfaces something useful instead
    // of falling through to the bare "Import failed" string. Each case below
    // documents one wire-format corruption and asserts the user-facing text.

    @Test
    fun `truncated file (shorter than envelope header) reports a clear error`() = runTest {
        val tooShort = ByteArray(8) // smaller than MAGIC + salt + iv
        val thrown = runCatching { service.import(tooShort, "pw") }.exceptionOrNull()
        assertTrue(
            "Expected an exception with a non-null message, got $thrown",
            thrown != null && !thrown.message.isNullOrBlank(),
        )
    }

    @Test
    fun `bad MAGIC reports 'Not a Haven backup file'`() = runTest {
        // Pad to envelope size so we get past length checks before the
        // MAGIC compare — proves the magic-mismatch path is the one that
        // fires (not a generic IndexOutOfBounds).
        val notHaven = ("BOGUS_HEADER_____").toByteArray() + ByteArray(64)
        val thrown = runCatching { service.import(notHaven, "pw") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
        assertEquals("Not a Haven backup file", thrown!!.message)
    }

    @Test
    fun `single-byte ciphertext flip surfaces as AEAD tag exception`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        val encrypted = service.export("pw").copyOf()
        // Flip a byte well inside the ciphertext (past MAGIC + salt + iv).
        encrypted[encrypted.size - 5] = (encrypted[encrypted.size - 5].toInt() xor 0xFF).toByte()

        val thrown = runCatching { service.import(encrypted, "pw") }.exceptionOrNull()
        assertTrue(
            "Expected AEADBadTagException, got ${thrown?.javaClass}",
            thrown is javax.crypto.AEADBadTagException,
        )
    }

    @Test
    fun `future schema version returns clear error rather than crashing`() = runTest {
        val json = JSONObject().apply {
            put("version", 999) // way beyond BACKUP_VERSION
            put("created", System.currentTimeMillis())
            put("connections", org.json.JSONArray())
            put("keys", org.json.JSONArray())
            put("knownHosts", org.json.JSONArray())
            put("portForwards", org.json.JSONArray())
            put("settings", JSONObject())
        }
        val encrypted = encryptForTest(json.toString().toByteArray(Charsets.UTF_8), "pw")

        val result = service.import(encrypted, "pw")
        assertEquals(0, result.count)
        assertEquals(1, result.errors.size)
        assertTrue(
            "Expected mention of newer version, got: ${result.errors.first()}",
            result.errors.first().contains("999") &&
                result.errors.first().contains("newer"),
        )
    }

    @Test
    fun `non-JSON plaintext after decrypt reports parse error`() = runTest {
        val encrypted = encryptForTest("this is not JSON".toByteArray(), "pw")
        val thrown = runCatching { service.import(encrypted, "pw") }.exceptionOrNull()
        assertTrue(
            "Expected JSONException-shaped failure with a message, got $thrown",
            thrown != null && !thrown.message.isNullOrBlank(),
        )
    }

    // -- Authenticator (TOTP) secrets (#510 migration gap) --

    /**
     * Backups silently omitted authenticator entries until v5.87.x. They are
     * the one thing here that cannot be re-derived from anything else: losing
     * them means going back to every service and re-enrolling by QR.
     */
    @Test
    fun `totp secrets survive an export import roundtrip`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        coEvery { totpSecretRepository.getAll() } returns listOf(
            sh.haven.core.data.db.entities.TotpSecret(
                id = "totp-1",
                label = "Router",
                secret = "JBSWY3DPEHPK3PXP",
                issuer = "OpenWrt",
                accountName = "root",
                algorithm = "SHA256",
                digits = 8,
                periodSeconds = 60,
                createdAt = 1700000000000L,
            ),
        )

        val saved = slot<sh.haven.core.data.db.entities.TotpSecret>()
        coEvery { totpSecretRepository.save(capture(saved)) } returns Unit

        val encrypted = service.export("testPassword123")
        service.import(encrypted, "testPassword123")

        // Every field, not just the secret: a wrong algorithm, digit count or
        // period produces codes that look plausible and never work.
        assertEquals("totp-1", saved.captured.id)
        assertEquals("Router", saved.captured.label)
        assertEquals("JBSWY3DPEHPK3PXP", saved.captured.secret)
        assertEquals("OpenWrt", saved.captured.issuer)
        assertEquals("root", saved.captured.accountName)
        assertEquals("SHA256", saved.captured.algorithm)
        assertEquals(8, saved.captured.digits)
        assertEquals(60, saved.captured.periodSeconds)
        assertEquals(1700000000000L, saved.captured.createdAt)
    }

    /** A backup written before v5.87.x has no `totp` key; that is not an error. */
    @Test
    fun `a backup without a totp section imports cleanly`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        coEvery { totpSecretRepository.getAll() } returns emptyList()

        val encrypted = service.export("pw")
        val result = service.import(encrypted, "pw")

        assertTrue("no errors expected, got ${result.errors}", result.errors.isEmpty())
    }

    @Test
    fun `saved chat conversations and messages survive export-import roundtrip (#807)`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        coEvery { totpSecretRepository.getAll() } returns emptyList()
        val conv = sh.haven.core.data.db.entities.ChatConversation(
            id = "conv-1",
            profileId = "prof-1",
            title = "OpenRouter test",
            createdAt = 1700000000000L,
            savedAt = 1700000001000L,
            updatedAt = 1700000002000L,
        )
        val msg = sh.haven.core.data.db.entities.ChatMessage(
            id = "msg-1",
            conversationId = "conv-1",
            role = "assistant",
            content = "Hello from deepseek:free",
            model = "deepseek/deepseek-chat:free",
            attachments = null,
            createdAt = 1700000002000L,
        )
        coEvery { chatRepository.getAllConversations() } returns listOf(conv)
        coEvery { chatRepository.getMessages("conv-1") } returns listOf(msg)

        val savedConv = slot<sh.haven.core.data.db.entities.ChatConversation>()
        val savedMsg = slot<sh.haven.core.data.db.entities.ChatMessage>()
        coEvery { chatRepository.saveConversation(capture(savedConv)) } returns Unit
        coEvery { chatRepository.saveMessage(capture(savedMsg)) } returns Unit

        val encrypted = service.export("pw123")
        val result = service.import(encrypted, "pw123")

        assertTrue(result.errors.isEmpty())
        assertEquals("conv-1", savedConv.captured.id)
        assertEquals("prof-1", savedConv.captured.profileId)
        assertEquals("OpenRouter test", savedConv.captured.title)
        assertEquals("msg-1", savedMsg.captured.id)
        assertEquals("conv-1", savedMsg.captured.conversationId)
        assertEquals("assistant", savedMsg.captured.role)
        assertEquals("Hello from deepseek:free", savedMsg.captured.content)
        assertEquals("deepseek/deepseek-chat:free", savedMsg.captured.model)
    }

    @Test
    fun `every persisted ConnectionProfile field survives export-import and v1 rdpColorDepth defaults to 32`() = runTest {
        val nonDefault = ConnectionProfile(
            id = "full-114",
            label = "Every Column",
            host = "192.0.2.99",
            port = 2202,
            username = "operator",
            sshPassword = "ssh-secret",
            authType = ConnectionProfile.AuthType.KEY,
            keyId = "key-99",
            authMethods = "KEY:key-99\nPASSWORD",
            totpConfirmBeforeSend = true,
            ignoreSavedKeys = true,
            colorTag = 4,
            lastConnected = 1711111111111L,
            sortOrder = 9,
            connectionType = "SAF",
            safTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocs",
            destinationHash = "deadbeefcafebabe",
            reticulumHost = "10.9.0.1",
            reticulumPort = 49999,
            jumpProfileId = "jump-99",
            sshOptions = "-o ServerAliveInterval=15",
            vncPort = 5909,
            vncUsername = "vnc-op",
            vncPassword = "vnc-secret",
            vncSshForward = false,
            vncSshProfileId = "vnc-ssh-99",
            vncColorDepth = "BPP_16_TRUE",
            sessionManager = "tmux",
            useMosh = true,
            useEternalTerminal = true,
            etPort = 2099,
            rdpPort = 3399,
            rdpUsername = "rdp-op",
            rdpDomain = "CORP",
            rdpPassword = "rdp-secret",
            rdpSshForward = true,
            rdpSshProfileId = "rdp-ssh-99",
            rdpUseNla = false,
            rdpColorDepth = 24,
            spicePort = 5930,
            spicePassword = "spice-secret",
            spiceSshForward = true,
            spiceSshProfileId = "spice-ssh-99",
            glkvmPort = 8443,
            glkvmPassword = "glkvm-secret",
            glkvmTargetLayout = "uk",
            glkvmSshForward = true,
            glkvmSshProfileId = "glkvm-ssh-99",
            smbPort = 1445,
            smbShare = "backups",
            smbDomain = "WORKGROUP",
            smbPassword = "smb-secret",
            smbSshForward = true,
            smbSshProfileId = "smb-ssh-99",
            proxyType = "SOCKS5",
            proxyHost = "127.0.0.1",
            proxyPort = 9050,
            proxyUser = "tor",
            proxyPassword = "proxy-secret",
            groupId = "grp-99",
            identityId = "ident-99",
            lastSessionName = "main|logs",
            disableAltScreen = true,
            rcloneRemoteName = "b2-crypt",
            rcloneProvider = "b2",
            emailProvider = "proton",
            emailServer = "127.0.0.1",
            emailSmtpServer = "127.0.0.2",
            emailPort = 1143,
            emailSmtpPort = 1025,
            emailTls = false,
            emailUsername = "user@proton.me",
            emailPassword = "email-secret",
            emailMailboxPassword = "mailbox-secret",
            emailAuthMethods = "PROTON_SRP",
            openaiPathPrefix = "/api",
            openaiApiKey = "sk-test-secret",
            aiProtocol = "ANTHROPIC",
            aiRouteType = "SSH",
            aiRouteProfileId = "ssh-ai-99",
            useAndroidShell = true,
            prootDistroId = "debian-trixie",
            moshServerCommand = "/usr/local/bin/mosh-server new -s",
            forwardAgent = true,
            addressFamily = "IPV6_ONLY",
            bindAddress = "fd00::1",
            reticulumNetworkName = "ifac-net",
            reticulumPassphrase = "ifac-secret",
            postLoginCommand = "uptime",
            sessionCommandOverride = "tmux new -A -s {name}",
            postLoginBeforeSessionManager = false,
            remoteCommand = "/bin/zsh -l",
            requestPty = false,
            usbForwardVidPid = "1050:0407",
            fileTransport = "SCP",
            tunnelConfigId = "tun-99",
            terminalColorScheme = "NORD",
            terminalBackgroundOpacity = 0.75f,
            autoReconnect = false,
            reconnectMaxAttempts = 12,
            reconnectOnNetworkChange = false,
            tunnelOnly = true,
            mcpEnabled = false,
            portKnockSequence = "7000/tcp 8000/udp",
            portKnockDelayMs = 250,
            spaKey = "spa-aes-secret",
            spaKeyBase64 = true,
            spaHmacKey = "spa-hmac-secret",
            spaHmacKeyBase64 = true,
            spaAccessSpec = "tcp/22",
            spaAllowMode = "EXPLICIT",
            spaExplicitIp = "203.0.113.10",
            spaPort = 62202,
            usbDriveSerial = "SN-USB-9999",
        )

        // Guard against future columns: every persisted field on nonDefault must differ
        // from the default ConnectionProfile so an omitted export/import key cannot hide
        // behind a default value.
        val defaults = ConnectionProfile(id = "default-id", label = "Default", host = "default.host", username = "default-user")
        val persistedFields = ConnectionProfile::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic && it.name != "ephemeral" }
        assertEquals(114, persistedFields.size)
        for (field in persistedFields) {
            field.isAccessible = true
            org.junit.Assert.assertNotEquals(
                "Test fixture must set a non-default value for ConnectionProfile.${field.name}",
                field.get(defaults),
                field.get(nonDefault),
            )
        }

        coEvery { connectionRepository.getAll() } returns listOf(nonDefault)
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val encrypted = service.export("pw")
        val captured = slot<ConnectionProfile>()
        coEvery { connectionRepository.save(capture(captured)) } returns Unit
        val result = service.import(encrypted, "pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())
        assertEquals(nonDefault, captured.captured)

        // Legacy backup omitting rdpColorDepth must default to ConnectionProfile's 32 (not 16).
        val legacyJson = JSONObject().apply {
            put("version", 1)
            put("created", 1700000000000L)
            put("connections", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "legacy-rdp")
                    put("label", "Win11")
                    put("host", "10.0.0.9")
                    put("port", 3389)
                    put("username", "admin")
                    put("connectionType", "RDP")
                })
            })
        }
        val legacyEnc = encryptForTest(legacyJson.toString().toByteArray(Charsets.UTF_8), "pw")
        service.import(legacyEnc, "pw")
        assertEquals(32, captured.captured.rdpColorDepth)
    }

    @Test
    fun `stringSet preferences roundtrip and device-bound backup_sync_passphrase is excluded`() = runTest {
        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns emptyList()
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()

        val disabledToolsKey = androidx.datastore.preferences.core.stringSetPreferencesKey("mcp_disabled_tools")
        val syncPassphraseKey = stringPreferencesKey("backup_sync_passphrase")
        dataStore.edit {
            it[disabledToolsKey] = setOf("ssh_exec", "write_file")
            it[syncPassphraseKey] = "ENC:device-a-keystore-ciphertext"
        }

        val encrypted = service.export("pw")

        dataStore.edit {
            it.clear()
            it[syncPassphraseKey] = "ENC:device-b-existing-ciphertext"
        }
        service.import(encrypted, "pw")

        val prefs = dataStore.data.first()
        assertEquals(setOf("ssh_exec", "write_file"), prefs[disabledToolsKey])
        assertEquals("ENC:device-b-existing-ciphertext", prefs[syncPassphraseKey])
    }

    @Test
    fun `every HavenDatabase entity is partitioned between BACKED_UP_ENTITIES and TRANSIENT_OR_DEVICE_SCOPED_ENTITIES`() {
        // @androidx.room.Database has BINARY retention, so resolve the @Database(entities = [...])
        // list from HavenDatabase.kt and cross-check its cardinality against Room's KSP-exported schema JSON.
        val dbSource = sequenceOf(
            java.io.File("src/main/kotlin/sh/haven/core/data/db/HavenDatabase.kt"),
            java.io.File("core/data/src/main/kotlin/sh/haven/core/data/db/HavenDatabase.kt"),
        ).first { it.isFile }.readText()
        val entitiesBlock = Regex("""@Database\(\s*entities\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(dbSource)?.groupValues?.get(1)
            ?: error("Could not locate @Database(entities = [...]) in HavenDatabase.kt")
        val declared = Regex("""([A-Za-z0-9_]+)::class""")
            .findAll(entitiesBlock)
            .map { Class.forName("sh.haven.core.data.db.entities.${it.groupValues[1]}") }
            .toSet()

        val schemaDir = sequenceOf(
            java.io.File("schemas/sh.haven.core.data.db.HavenDatabase"),
            java.io.File("core/data/schemas/sh.haven.core.data.db.HavenDatabase"),
        ).first { it.isDirectory }
        val latestSchema = schemaDir.listFiles { f -> f.extension == "json" }!!
            .maxBy { it.nameWithoutExtension.toInt() }
        val schemaEntities = JSONObject(latestSchema.readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        assertEquals(
            "Parsed @Database entities must match Room KSP schema ${latestSchema.name}",
            schemaEntities.length(),
            declared.size,
        )

        val overlap = BackupService.BACKED_UP_ENTITIES intersect BackupService.TRANSIENT_OR_DEVICE_SCOPED_ENTITIES
        assertTrue("Entity sets must be disjoint, overlap=$overlap", overlap.isEmpty())
        assertEquals(
            "Every @Database entity in HavenDatabase must be backed up or explicitly classified as transient/device-scoped",
            declared,
            BackupService.BACKED_UP_ENTITIES + BackupService.TRANSIENT_OR_DEVICE_SCOPED_ENTITIES,
        )
    }

    @Test
    fun `newly covered entities and SshKey plus TunnelConfig columns survive cross-device roundtrip`() = runTest {
        val age = sh.haven.core.data.db.entities.AgeIdentityEntity(
            id = "age-1",
            label = "Vault Key",
            recipient = "age1ql3z7hjy54pw3hyww5ayyfg7zqgvc7w3j2elw8zmrj2kg5sfn9aqmcac8p",
            secret = "AGE-SECRET-KEY-1GFP3Z7HJY54PW3HYWW5AYYFG7ZQGVC7W3J2ELW8ZMRJ2KG5SFN9AQ00000",
            createdAt = 1700000010000L,
        )
        val wsProfile = sh.haven.core.data.db.entities.WorkspaceProfile(
            id = "ws-1",
            name = "Morning Ops",
            sortOrder = 2,
            createdAt = 1700000020000L,
            updatedAt = 1700000030000L,
        )
        val wsItems = listOf(
            sh.haven.core.data.db.entities.WorkspaceItem(
                id = "wsi-1",
                workspaceId = "ws-1",
                kind = sh.haven.core.data.db.entities.WorkspaceItem.Kind.TERMINAL,
                connectionProfileId = "c1",
                path = null,
                sessionName = "ops",
                sortOrder = 0,
            ),
            sh.haven.core.data.db.entities.WorkspaceItem(
                id = "wsi-2",
                workspaceId = "ws-1",
                kind = sh.haven.core.data.db.entities.WorkspaceItem.Kind.WAYLAND,
                connectionProfileId = null,
                path = null,
                sessionName = null,
                sortOrder = 1,
            ),
        )
        val syncProfile = sh.haven.core.data.db.entities.SyncProfile(
            id = "sync-1",
            name = "Photos Backup",
            srcFs = "/storage/emulated/0/DCIM",
            dstFs = "b2:photos",
            mode = "COPY",
            includePatterns = "*.jpg\n*.png",
            excludePatterns = ".thumbnails/**",
            minSize = "10k",
            maxSize = "500M",
            bandwidthLimit = "5M",
            createdAt = 1700000040000L,
            lastRunAt = 1700000050000L,
        )
        val stepCa = sh.haven.core.data.db.entities.StepCaConfig(
            id = "ca-1",
            name = "Corp CA",
            caUrl = "https://ca.corp.internal:9000",
            oidcIssuer = "https://idp.corp.internal",
            oidcAuthUrl = "https://idp.corp.internal/authorize",
            oidcTokenUrl = "https://idp.corp.internal/token",
            oidcClientId = "step-ca",
            oidcClientSecret = "semi-public-secret",
            provisioner = "sso",
            defaultPrincipals = "ops,root",
            rootCertPem = "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----",
            sshHostCaPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHostCa",
            createdAt = 1700000060000L,
        )
        val tlsCert = sh.haven.core.data.db.entities.KnownTlsCert(
            id = 42,
            hostname = "kvm.corp.internal",
            port = 443,
            sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            firstSeen = 1700000070000L,
        )
        val mailRule = sh.haven.core.data.db.entities.MailRule(
            id = "mr-1",
            name = "PagerDuty Alerts",
            enabled = false,
            orderIndex = 3,
            accountProfileId = "mail-1",
            folderId = "Alerts",
            criteriaJson = """{"from":"alerts@pagerduty.com"}""",
            actionsJson = """[{"type":"star"}]""",
            stopOnMatch = true,
            notifyOnFire = true,
            createdAt = 1700000080000L,
            lastFiredAt = 1700000090000L,
        )
        val sshKey = SshKey(
            id = "k-cert",
            label = "Signed Key",
            keyType = "ED25519",
            privateKeyBytes = byteArrayOf(9, 8, 7, 6),
            publicKeyOpenSsh = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAISigned",
            fingerprintSha256 = "SHA256:signed",
            createdAt = 1700000100000L,
            isEncrypted = true,
            biometricProtected = true,
            certificateBytes = "ssh-ed25519-cert-v01@openssh.com AAAA...".toByteArray(),
            caConfigId = "ca-1",
            certIssuedAt = 1700000110000L,
            enabledForAuth = false,
            passphraseEncrypted = "ENC:device-a-wrapped",
        )
        val ownedTunnel = TunnelConfig(
            id = "tun-owned",
            label = "Inline CF",
            type = "CLOUDFLARE_ACCESS",
            configText = """{"hostname":"ssh.example.com"}""".toByteArray(),
            createdAt = 1700000120000L,
            ownerProfileId = "c1",
        )

        coEvery { connectionRepository.getAll() } returns emptyList()
        coEvery { sshKeyDao.getAll() } returns listOf(sshKey)
        coEvery { sshKeyRepository.getAllDecrypted() } returns listOf(sshKey)
        coEvery { sshKeyRepository.getStoredPassphrase("k-cert") } returns "key-passphrase-plain"
        coEvery { knownHostDao.getAll() } returns emptyList()
        coEvery { portForwardRuleDao.getAll() } returns emptyList()
        coEvery { tunnelConfigRepository.getAllDecrypted() } returns listOf(ownedTunnel)
        coEvery { ageIdentityRepository.getAllDecrypted() } returns listOf(age)
        coEvery { workspaceRepository.getAllWithItems() } returns listOf(
            sh.haven.core.data.repository.WorkspaceWithItems(wsProfile, wsItems),
        )
        coEvery { syncProfileRepository.getAll() } returns listOf(syncProfile)
        coEvery { stepCaConfigRepository.getAll() } returns listOf(stepCa)
        coEvery { knownTlsCertDao.getAll() } returns listOf(tlsCert)
        coEvery { mailRuleRepository.getAllRules() } returns listOf(mailRule)

        val encrypted = service.export("cross-device-pw")

        val b = newImporterService()
        val savedAge = slot<sh.haven.core.data.db.entities.AgeIdentityEntity>()
        val savedWsProfile = slot<sh.haven.core.data.db.entities.WorkspaceProfile>()
        val savedWsItems = slot<List<sh.haven.core.data.db.entities.WorkspaceItem>>()
        val savedSync = slot<sh.haven.core.data.db.entities.SyncProfile>()
        val savedCa = slot<sh.haven.core.data.db.entities.StepCaConfig>()
        val savedTls = slot<sh.haven.core.data.db.entities.KnownTlsCert>()
        val savedMailRule = slot<sh.haven.core.data.db.entities.MailRule>()
        val savedKey = slot<SshKey>()
        val savedTunnel = slot<TunnelConfig>()

        coEvery { b.ageIdentityRepository.savePlaintext(capture(savedAge)) } returns Unit
        coEvery { b.workspaceRepository.restoreFromBackup(capture(savedWsProfile), capture(savedWsItems)) } returns Unit
        coEvery { b.syncProfileRepository.save(capture(savedSync)) } returns Unit
        coEvery { b.stepCaConfigRepository.save(capture(savedCa)) } returns Unit
        coEvery { b.knownTlsCertDao.upsert(capture(savedTls)) } returns Unit
        coEvery { b.mailRuleRepository.saveRule(capture(savedMailRule)) } returns Unit
        coEvery { b.sshKeyRepository.save(capture(savedKey)) } returns Unit
        coEvery { b.tunnelConfigRepository.save(capture(savedTunnel)) } returns Unit

        val result = b.service.import(encrypted, "cross-device-pw")
        assertTrue("Import errors: ${result.errors}", result.errors.isEmpty())

        assertEquals(age, savedAge.captured)
        assertEquals(wsProfile, savedWsProfile.captured)
        assertEquals(wsItems, savedWsItems.captured)
        assertEquals(syncProfile, savedSync.captured)
        assertEquals(stepCa.id, savedCa.captured.id)
        assertEquals(stepCa.oidcClientSecret, savedCa.captured.oidcClientSecret)
        assertEquals(stepCa.sshHostCaPublicKey, savedCa.captured.sshHostCaPublicKey)
        assertEquals(stepCa.rootCertPem, savedCa.captured.rootCertPem)
        assertEquals(tlsCert.copy(id = 0), savedTls.captured)
        assertEquals(mailRule, savedMailRule.captured)
        assertEquals(sshKey.copy(passphraseEncrypted = null), savedKey.captured)
        coVerify(exactly = 1) { b.sshKeyRepository.setStoredPassphrase("k-cert", "key-passphrase-plain") }
        assertEquals("c1", savedTunnel.captured.ownerProfileId)
    }
}
