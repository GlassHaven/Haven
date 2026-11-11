package sh.haven.app.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.SshIdentityRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.mcp.McpError
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.HostKeyResult
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.KnownHostEntry
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

/**
 * Pins the run_command executor's decision tree (#367): reuse-first, then a
 * headless connect that fails CLOSED on unknown/changed host keys and on
 * credentials that would need a UI prompt. Everything here is mocked — no
 * sockets; the network truth comes from the device E2E, this pins the
 * routing and refusal logic around it.
 */
class HeadlessSshExecTest {

    private val sshProfile = ConnectionProfile(
        id = "p1",
        label = "server",
        host = "10.0.0.1",
        username = "u",
        sshPassword = "pw",
    )

    private fun newExec(
        connectionRepository: ConnectionRepository = mockk(relaxed = true),
        sshSessionManager: SshSessionManager = mockk(relaxed = true) {
            every { getSshClientForProfile(any()) } returns null
        },
        sshKeyRepository: SshKeyRepository = mockk(relaxed = true) {
            coEvery { getAllDecrypted() } returns emptyList()
        },
        sshIdentityRepository: SshIdentityRepository = mockk {
            coEvery { applyTo(any()) } answers { firstArg() }
        },
        hostKeyVerifier: HostKeyVerifier = mockk(),
        client: SshClient = mockk(relaxed = true),
    ): HeadlessSshExec = HeadlessSshExec(
        connectionRepository = connectionRepository,
        sshSessionManager = sshSessionManager,
        sshKeyRepository = sshKeyRepository,
        sshIdentityRepository = sshIdentityRepository,
        hostKeyVerifier = hostKeyVerifier,
        hostRediscovery = mockk(relaxed = true) {
            coEvery { rediscover(any()) } returns null
        },
    ).apply { clientFactory = { client } }

    private fun expectMcpError(contains: String, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected McpError containing '$contains'")
        } catch (e: McpError) {
            assertTrue(
                "message '${e.message}' should contain '$contains'",
                e.message.orEmpty().contains(contains, ignoreCase = true),
            )
        }
    }

    @Test
    fun `unknown profile is refused`() {
        val repo = mockk<ConnectionRepository> { coEvery { getById("nope") } returns null }
        val exec = newExec(connectionRepository = repo)
        expectMcpError("Unknown connection profile") { exec.run("nope", "uptime", 5_000) }
    }

    @Test
    fun `non-SSH profile is refused`() {
        val repo = mockk<ConnectionRepository> {
            coEvery { getById("p1") } returns sshProfile.copy(connectionType = "VNC")
        }
        val exec = newExec(connectionRepository = repo)
        expectMcpError("not SSH") { exec.run("p1", "uptime", 5_000) }
    }

    @Test
    fun `live connection is reused without a host-key check or fresh connect`() {
        val live = mockk<SshClient> {
            coEvery { execCommand("uptime", 5_000) } returns ExecResult(0, "up", "")
        }
        val repo = mockk<ConnectionRepository> { coEvery { getById("p1") } returns sshProfile }
        val sessions = mockk<SshSessionManager> { every { getSshClientForProfile("p1") } returns live }
        val verifier = mockk<HostKeyVerifier>() // no stubs: any call would throw
        val exec = newExec(connectionRepository = repo, sshSessionManager = sessions, hostKeyVerifier = verifier)

        val outcome = runBlocking { exec.run("p1", "uptime", 5_000) }

        assertTrue(outcome.reusedLiveConnection)
        assertEquals(0, outcome.exec.exitStatus)
        assertEquals("up", outcome.exec.stdout)
    }

    @Test
    fun `stale live client falls through to a headless connect`() {
        val stale = mockk<SshClient> {
            coEvery { execCommand(any(), any()) } throws IllegalStateException("Not connected")
        }
        val fresh = mockk<SshClient>(relaxed = true) {
            coEvery { connect(any()) } returns mockk<KnownHostEntry>()
            coEvery { execCommand("uptime", 5_000) } returns ExecResult(0, "up", "")
        }
        val repo = mockk<ConnectionRepository> { coEvery { getById("p1") } returns sshProfile }
        val sessions = mockk<SshSessionManager> { every { getSshClientForProfile("p1") } returns stale }
        val verifier = mockk<HostKeyVerifier> {
            coEvery { verify(any()) } returns HostKeyResult.Trusted
            coEvery { trustedHostCaKeys() } returns emptyList()
        }
        val exec = newExec(
            connectionRepository = repo, sshSessionManager = sessions,
            hostKeyVerifier = verifier, client = fresh,
        )

        val outcome = runBlocking { exec.run("p1", "uptime", 5_000) }

        assertFalse(outcome.reusedLiveConnection)
        assertEquals("up", outcome.exec.stdout)
        coVerify { fresh.disconnect() }
    }

    @Test
    fun `unknown host key is refused fail-closed and the client disconnected`() {
        val client = mockk<SshClient>(relaxed = true) {
            coEvery { connect(any()) } returns mockk<KnownHostEntry>()
        }
        val repo = mockk<ConnectionRepository> { coEvery { getById("p1") } returns sshProfile }
        val verifier = mockk<HostKeyVerifier> {
            coEvery { verify(any()) } returns HostKeyResult.NewHost(mockk())
            coEvery { trustedHostCaKeys() } returns emptyList()
        }
        val exec = newExec(connectionRepository = repo, hostKeyVerifier = verifier, client = client)

        expectMcpError("Unknown host key") { exec.run("p1", "uptime", 5_000) }
        coVerify(exactly = 0) { client.execCommand(any(), any()) }
        coVerify { client.disconnect() }
    }

    @Test
    fun `changed host key is refused`() {
        val client = mockk<SshClient>(relaxed = true) {
            coEvery { connect(any()) } returns mockk<KnownHostEntry>()
        }
        val repo = mockk<ConnectionRepository> { coEvery { getById("p1") } returns sshProfile }
        val verifier = mockk<HostKeyVerifier> {
            coEvery { verify(any()) } returns HostKeyResult.KeyChanged(mockk(), mockk())
            coEvery { trustedHostCaKeys() } returns emptyList()
        }
        val exec = newExec(connectionRepository = repo, hostKeyVerifier = verifier, client = client)

        expectMcpError("HOST KEY CHANGED") { exec.run("p1", "uptime", 5_000) }
        coVerify(exactly = 0) { client.execCommand(any(), any()) }
    }

    @Test
    fun `profile with no headless-usable credential gets an actionable error`() {
        val repo = mockk<ConnectionRepository> {
            coEvery { getById("p1") } returns sshProfile.copy(sshPassword = null)
        }
        val exec = newExec(connectionRepository = repo)
        expectMcpError("Connect it in Haven first") { exec.run("p1", "uptime", 5_000) }
    }

    @Test
    fun `headless auth excludes encrypted and FIDO2 keys, falls back to password`() = runBlocking {
        val keyRepo = mockk<SshKeyRepository>(relaxed = true) {
            coEvery { getAllDecrypted() } returns emptyList()
        }
        // keyId set but the key is encrypted → null (would need a passphrase prompt).
        coEvery { keyRepo.getById("k1") } returns mockk(relaxed = true) {
            every { keyType } returns "ssh-ed25519"
            every { isEncrypted } returns true
        }
        assertEquals(null, HeadlessSshAuth.resolve(sshProfile.copy(keyId = "k1"), keyRepo))

        // sk- (FIDO2) key → null (would need a touch prompt).
        coEvery { keyRepo.getById("k2") } returns mockk(relaxed = true) {
            every { keyType } returns "sk-ssh-ed25519@openssh.com"
            every { isEncrypted } returns false
        }
        assertEquals(null, HeadlessSshAuth.resolve(sshProfile.copy(keyId = "k2"), keyRepo))

        // No key at all → stored password.
        val auth = HeadlessSshAuth.resolve(sshProfile, keyRepo)
        assertTrue(auth is sh.haven.core.ssh.ConnectionConfig.AuthMethod.Password)
    }

    @Test
    fun `headless auth excludes security-chip keys`() = runBlocking {
        val keyRepo = mockk<SshKeyRepository>(relaxed = true) {
            coEvery { getAllDecrypted() } returns emptyList()
        }
        // A hardware-resident key: signing needs the per-op biometric
        // ceremony with the app foregrounded, which a headless connect
        // (run_command, agent tunnels) cannot raise — same exclusion as
        // the sk- touch prompt above.
        coEvery { keyRepo.getById("k-chip") } returns mockk(relaxed = true) {
            every { keyType } returns "ecdsa-sha2-nistp256"
            every { isEncrypted } returns false
            every { hardwareAlias } returns "haven-ssh-ab12cd34"
        }
        assertEquals(null, HeadlessSshAuth.resolve(sshProfile.copy(keyId = "k-chip"), keyRepo))
    }

    @Test
    fun `headless auth auto-offer excludes hardwareAlias and disabled keys and honours ignoreSavedKeys`() = runBlocking {
        val hwKey = sh.haven.core.data.db.entities.SshKey(
            id = "k-hw",
            label = "chip",
            keyType = "ecdsa-sha2-nistp256",
            privateKeyBytes = ByteArray(0),
            publicKeyOpenSsh = "ecdsa-sha2-nistp256 AAAA",
            fingerprintSha256 = "SHA256:hw",
            hardwareAlias = "haven-ssh-chip1",
        )
        val disabledGen = sh.haven.core.security.SshKeyGenerator.generate(
            sh.haven.core.security.SshKeyGenerator.KeyType.ED25519,
            "off",
        )
        val disabledKey = sh.haven.core.data.db.entities.SshKey(
            id = "k-off",
            label = "disabled",
            keyType = disabledGen.type.sshName,
            privateKeyBytes = disabledGen.privateKeyBytes,
            publicKeyOpenSsh = disabledGen.publicKeyOpenSsh,
            fingerprintSha256 = disabledGen.fingerprintSha256,
            enabledForAuth = false,
        )
        val goodKeyGen = sh.haven.core.security.SshKeyGenerator.generate(
            sh.haven.core.security.SshKeyGenerator.KeyType.ED25519,
            "good",
        )
        val goodKey = sh.haven.core.data.db.entities.SshKey(
            id = "k-good",
            label = "good",
            keyType = goodKeyGen.type.sshName,
            privateKeyBytes = goodKeyGen.privateKeyBytes,
            publicKeyOpenSsh = goodKeyGen.publicKeyOpenSsh,
            fingerprintSha256 = goodKeyGen.fingerprintSha256,
            enabledForAuth = true,
        )
        val keyRepo = mockk<SshKeyRepository>(relaxed = true) {
            coEvery { getAllDecrypted() } returns listOf(hwKey, disabledKey, goodKey)
        }

        // Auto-offer must exclude both hardwareAlias!=null (empty privateKeyBytes would throw or fail)
        // and enabledForAuth=false, offering only goodKey.
        val offered = HeadlessSshAuth.resolve(sshProfile.copy(keyId = null), keyRepo)
        assertTrue(offered is sh.haven.core.ssh.ConnectionConfig.AuthMethod.PrivateKeys)
        offered as sh.haven.core.ssh.ConnectionConfig.AuthMethod.PrivateKeys
        assertEquals(listOf("good"), offered.keys.map { it.label })

        // When ignoreSavedKeys=true, auto-offer is skipped and password fallback is used.
        val passwordOnly = HeadlessSshAuth.resolve(
            sshProfile.copy(keyId = null, ignoreSavedKeys = true),
            keyRepo,
        )
        assertTrue(passwordOnly is sh.haven.core.ssh.ConnectionConfig.AuthMethod.Password)
    }
}
