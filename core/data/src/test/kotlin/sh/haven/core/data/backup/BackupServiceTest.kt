package sh.haven.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import java.io.File

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

        val prefsFile = File(tempFolder.root, "test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create { prefsFile }

        service = BackupService(connectionDao, connectionRepository, connectionGroupDao, sshKeyDao, sshKeyRepository, knownHostDao, portForwardRuleDao, dataStore)
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
}
