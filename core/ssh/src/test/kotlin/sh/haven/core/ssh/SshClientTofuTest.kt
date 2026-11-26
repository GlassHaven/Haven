package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.AcceptAllPasswordAuthenticator
import org.apache.sshd.server.auth.password.RejectAllPasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * Ground-truth unit tests for the TOFU host-key bypass bug reported in the
 * GlassOnTin/Haven#75 comment thread (v4.51.0).
 *
 * These tests stand up a real Apache MINA sshd server in-process on loopback
 * and drive [SshClient] through a full KEX + auth handshake. MINA sshd runs
 * on the host JVM only — it does not work on Android ART due to
 * `ServiceLoader` / crypto-provider differences — so the tests live in
 * `src/test` (plain JUnit), not `src/androidTest`. The [SshClient] code
 * under test is pure Kotlin/JSch and behaves identically on JVM and ART,
 * so the bug reproduction is faithful.
 *
 * The key assertion is in [connect_whenAuthFails_surfacesHostKeyToCaller]:
 * after a failed auth, the caller must be able to retrieve the host key
 * captured during KEX, so the TOFU prompt can be shown on first contact.
 *
 * - Before the fix: test FAILS with a raw `com.jcraft.jsch.JSchException`
 *   from `sess.connect()`, no host key reaches the caller.
 * - After the fix:  test PASSES — [SshClient.connect] raises
 *   [HostKeyAuthFailure] carrying the captured [KnownHostEntry].
 */
class SshClientTofuTest {

    private lateinit var server: SshServer
    private var serverPort: Int = 0

    @Before
    fun startServer() {
        server = buildServer(acceptAuth = false)
        server.start()
        serverPort = server.port
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) {
            server.stop(true)
        }
    }

    /**
     * REGRESSION TEST for GlassOnTin/Haven#75 comment thread.
     *
     * On a fresh install the user's first auth attempt may fail (passphrase-
     * protected keys tried with null passphrase, wrong remembered password,
     * MaxAuthTries tripped by many stored keys). In that case the user never
     * sees the host-key fingerprint prompt, even though KEX completed and
     * the key is known — because [SshClient.connect] currently lets JSch's
     * auth exception propagate from inside `sess.connect()`, short-circuiting
     * [SshClient.extractHostKey].
     *
     * Expected post-fix behaviour: a successful KEX followed by failed auth
     * surfaces as [HostKeyAuthFailure] carrying the captured [KnownHostEntry],
     * so the caller can drive TOFU verification and show the fingerprint
     * prompt before reporting the auth error.
     */
    @Test
    fun connect_whenAuthFails_surfacesHostKeyToCaller() {
        val client = SshClient()
        val config = ConnectionConfig(
            host = "127.0.0.1",
            port = serverPort,
            username = "anyuser",
            authMethod = ConnectionConfig.AuthMethod.Password("definitely-wrong"),
        )

        val thrown = assertThrows(HostKeyAuthFailure::class.java) {
            runBlocking { client.connect(config, connectTimeoutMs = 15_000) }
        }

        assertNotNull("host key must be captured from KEX", thrown.hostKey)
        assertEquals("127.0.0.1", thrown.hostKey.hostname)
        assertEquals(serverPort, thrown.hostKey.port)
        assertTrue(
            "key type must be populated, got='${thrown.hostKey.keyType}'",
            thrown.hostKey.keyType.isNotBlank(),
        )
        assertTrue(
            "public key b64 must be populated, got length=${thrown.hostKey.publicKeyBase64.length}",
            thrown.hostKey.publicKeyBase64.isNotBlank(),
        )
        // The underlying JSch auth failure must be preserved so callers can
        // still show an intelligible error message to the user.
        assertNotNull("cause chain must be preserved", thrown.cause)
    }

    /**
     * Sanity check: when auth succeeds, [SshClient.connect] returns the host
     * key directly (pre-existing behaviour, must not regress).
     */
    @Test
    fun connect_whenAuthSucceeds_returnsHostKey() {
        server.stop(true)
        server = buildServer(acceptAuth = true)
        server.start()
        serverPort = server.port

        val client = SshClient()
        try {
            val config = ConnectionConfig(
                host = "127.0.0.1",
                port = serverPort,
                username = "anyuser",
                authMethod = ConnectionConfig.AuthMethod.Password("accepted"),
            )
            val hostKey = runBlocking { client.connect(config, connectTimeoutMs = 15_000) }
            assertNotNull("plain-key host must surface a TOFU entry", hostKey)
            assertEquals("127.0.0.1", hostKey!!.hostname)
            assertEquals(serverPort, hostKey.port)
            assertTrue(hostKey.keyType.isNotBlank())
            assertTrue(hostKey.publicKeyBase64.isNotBlank())
        } finally {
            client.disconnect()
        }
    }

    /**
     * Security regression test (RFC 4251 §4.1 / CWE-295): when the caller's
     * pre-authentication [hostKeyGate] rejects an unknown or changed host key
     * during KEX, JSch MUST abort immediately after KEX with ZERO password or
     * keyboard-interactive authentication attempts reaching the server.
     */
    @Test
    fun connect_whenHostKeyGateRejects_sendsZeroPasswordOrKeyboardInteractiveAttempts() {
        val passwordsReceived = java.util.concurrent.CopyOnWriteArrayList<String>()
        val kiChallengesIssued = java.util.concurrent.atomic.AtomicInteger(0)
        server.stop(true)
        server = buildAuthObservingServer(
            acceptPassword = "super-secret-password",
            passwordsReceived = passwordsReceived,
            kiChallengesIssued = kiChallengesIssued,
        )
        server.start()
        serverPort = server.port

        val client = SshClient()
        var gateSeen: KnownHostEntry? = null
        try {
            val config = ConnectionConfig(
                host = "127.0.0.1",
                port = serverPort,
                username = "alice",
                authMethod = ConnectionConfig.AuthMethod.Password("super-secret-password"),
            )
            val thrown = assertThrows(SshHostKeyRejectedException::class.java) {
                runBlocking {
                    client.connect(
                        config = config,
                        connectTimeoutMs = 15_000,
                        totpCodeProvider = { "654321" },
                        keyboardInteractivePrompter = { listOf("654321") },
                        hostKeyGate = { entry ->
                            gateSeen = entry
                            throw SshHostKeyRejectedException(entry, "MITM host key rejected")
                        },
                    )
                }
            }
            assertNotNull("hostKeyGate must inspect the server's KEX host key", gateSeen)
            assertEquals("MITM host key rejected", thrown.message)
            assertTrue(
                "MITM server must receive ZERO passwords before hostKeyGate rejects, got: $passwordsReceived",
                passwordsReceived.isEmpty(),
            )
            assertEquals(
                "MITM server must issue ZERO keyboard-interactive challenges before hostKeyGate rejects",
                0,
                kiChallengesIssued.get(),
            )
            org.junit.Assert.assertFalse("rejected session must be disconnected", client.isConnected)
        } finally {
            client.disconnect()
        }
    }

    /**
     * Same pre-auth guarantee on the synchronous [SshClient.connectBlocking]
     * path used by background auto-reconnect.
     */
    @Test
    fun connectBlocking_whenHostKeyGateRejects_sendsZeroPasswords() {
        val passwordsReceived = java.util.concurrent.CopyOnWriteArrayList<String>()
        val kiChallengesIssued = java.util.concurrent.atomic.AtomicInteger(0)
        server.stop(true)
        server = buildAuthObservingServer(
            acceptPassword = "reconnect-secret-password",
            passwordsReceived = passwordsReceived,
            kiChallengesIssued = kiChallengesIssued,
        )
        server.start()
        serverPort = server.port

        val client = SshClient()
        try {
            val config = ConnectionConfig(
                host = "127.0.0.1",
                port = serverPort,
                username = "alice",
                authMethod = ConnectionConfig.AuthMethod.Password("reconnect-secret-password"),
            )
            assertThrows(SshHostKeyRejectedException::class.java) {
                client.connectBlocking(
                    config = config,
                    connectTimeoutMs = 15_000,
                    hostKeyGate = { entry ->
                        throw SshHostKeyRejectedException(entry, "Host key changed during reconnect")
                    },
                )
            }
            assertTrue(
                "MITM server must receive ZERO passwords on rejected reconnect, got: $passwordsReceived",
                passwordsReceived.isEmpty(),
            )
            assertEquals(0, kiChallengesIssued.get())
            org.junit.Assert.assertFalse(client.isConnected)
        } finally {
            client.disconnect()
        }
    }

    /**
     * Positive ordering + single-handshake proof: when [hostKeyGate] approves
     * the key, it runs BEFORE password authentication (verified by asserting
     * `passwordsReceived.isEmpty()` inside the callback itself) and completes
     * authentication on the SAME single SSH session without reconnecting.
     */
    @Test
    fun connect_whenHostKeyGateApproves_runsBeforePasswordAuthOnSingleHandshake() {
        val passwordsReceived = java.util.concurrent.CopyOnWriteArrayList<String>()
        val kiChallengesIssued = java.util.concurrent.atomic.AtomicInteger(0)
        val sessionsCreated = java.util.concurrent.atomic.AtomicInteger(0)
        server.stop(true)
        server = buildAuthObservingServer(
            acceptPassword = "approved-secret-password",
            passwordsReceived = passwordsReceived,
            kiChallengesIssued = kiChallengesIssued,
            sessionsCreated = sessionsCreated,
        )
        server.start()
        serverPort = server.port

        val client = SshClient()
        var gateCalls = 0
        try {
            val config = ConnectionConfig(
                host = "127.0.0.1",
                port = serverPort,
                username = "alice",
                authMethod = ConnectionConfig.AuthMethod.Password("approved-secret-password"),
            )
            val entry = runBlocking {
                client.connect(
                    config = config,
                    connectTimeoutMs = 15_000,
                    hostKeyGate = { seen ->
                        gateCalls++
                        assertTrue(
                            "hostKeyGate MUST execute before any password is sent, got $passwordsReceived",
                            passwordsReceived.isEmpty(),
                        )
                        assertEquals("127.0.0.1", seen.hostname)
                    },
                )
            }
            assertEquals("hostKeyGate must run exactly once", 1, gateCalls)
            assertEquals("must complete on a single SSH handshake", 1, sessionsCreated.get())
            assertEquals(listOf("approved-secret-password"), passwordsReceived.toList())
            assertNotNull(entry)
            assertTrue(client.isConnected)
        } finally {
            client.disconnect()
        }
    }

    /**
     * Stand up a minimal MINA sshd server on 127.0.0.1:ephemeral with an
     * in-process RSA host key. Only password auth is offered (publickey
     * auth is disabled) so JSch picks password unambiguously.
     */
    private fun buildServer(acceptAuth: Boolean): SshServer {
        // MINA's SimpleGeneratorHostKeyProvider tries to LOAD any existing file
        // at the given path. createTempFile creates a 0-byte file, which fails
        // deserialization. Reserve a unique path and delete the placeholder so
        // MINA will generate and persist a fresh key on first use.
        val keyFile = Files.createTempFile("haven-test-hostkey-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }

        return SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0 // ephemeral — read actual port via server.port after start()
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                // This is a JCE algorithm name ("RSA"), not an SSH protocol
                // name ("ssh-rsa") — the latter fails with NoSuchAlgorithmException.
                algorithm = "RSA"
                keySize = 2048
                // Eagerly materialize the RSA-2048 key before server.start() so prime
                // search on a CPU-contended CI runner never runs inside JSch's KEX timeout.
                loadKeys(null)
            }
            passwordAuthenticator = if (acceptAuth) {
                AcceptAllPasswordAuthenticator.INSTANCE
            } else {
                RejectAllPasswordAuthenticator.INSTANCE
            }
            // publickeyAuthenticator left null → pubkey auth disabled
        }
    }

    private fun buildAuthObservingServer(
        acceptPassword: String,
        passwordsReceived: MutableList<String>,
        kiChallengesIssued: java.util.concurrent.atomic.AtomicInteger,
        sessionsCreated: java.util.concurrent.atomic.AtomicInteger? = null,
    ): SshServer {
        val keyFile = Files.createTempFile("haven-test-hostkey-obs-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }
        return SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                algorithm = "RSA"
                keySize = 2048
                loadKeys(null)
            }
            sessionsCreated?.let { counter ->
                addSessionListener(object : org.apache.sshd.common.session.SessionListener {
                    override fun sessionCreated(session: org.apache.sshd.common.session.Session) {
                        counter.incrementAndGet()
                    }
                })
            }
            userAuthFactories = listOf(
                org.apache.sshd.server.auth.password.UserAuthPasswordFactory.INSTANCE,
                org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory.INSTANCE,
            )
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ ->
                passwordsReceived.add(password)
                password == acceptPassword
            }
            keyboardInteractiveAuthenticator = object : org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator {
                override fun generateChallenge(
                    session: org.apache.sshd.server.session.ServerSession,
                    username: String,
                    lang: String,
                    subMethods: String,
                ): org.apache.sshd.server.auth.keyboard.InteractiveChallenge {
                    kiChallengesIssued.incrementAndGet()
                    return org.apache.sshd.server.auth.keyboard.InteractiveChallenge().apply {
                        interactionName = "OTP"
                        interactionInstruction = ""
                        languageTag = ""
                        addPrompt("Verification code: ", false)
                    }
                }

                override fun authenticate(
                    session: org.apache.sshd.server.session.ServerSession,
                    username: String,
                    responses: List<String>,
                ): Boolean = false
            }
        }
    }
}
