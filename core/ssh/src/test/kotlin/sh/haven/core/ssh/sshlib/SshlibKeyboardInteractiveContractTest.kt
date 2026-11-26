package sh.haven.core.ssh.sshlib

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.RejectAllPasswordAuthenticator
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.SshConnection
import java.nio.file.Files

/**
 * Keyboard-interactive parity for the sshlib engine (#58): the engine must
 * answer KI rounds by the same rules as JSch — TOTP codes auto-submitted with
 * no UI, anything else routed to the user's prompter, a saved password
 * silently satisfying a "Password:" round.
 *
 * The JSch leg of these rules is covered by `SshClientTotpAuthTest` and
 * `KeyboardInteractiveUserInfoTest`; both engines now share
 * [sh.haven.core.ssh.KeyboardInteractiveAnswerer], and these tests pin that the
 * sshlib engine actually reaches it.
 *
 * **The challenge here is deliberately unanswerable from stored secrets.** The
 * server asks "What is 2+2?" — no password and no TOTP heuristic produces "4",
 * so a regression that drops the prompter (or falls back to echoing the saved
 * password, which is what this engine did before) fails the test instead of
 * passing by accident. Verified by mutation: forcing `ki = null` in
 * [SshlibConnection.connect] fails all four.
 *
 * MINA sshd runs on the host JVM only, so this lives in `src/test`.
 */
class SshlibKeyboardInteractiveContractTest {

    private lateinit var server: SshServer
    private var serverPort: Int = 0
    private val totpCode = "424242"

    // A loaded CI runner can spend seconds just scheduling the embedded-server
    // auth round-trips; 5s timed out prompterAnswersNonSecretChallenge in CI
    // (run 44260256682) while passing idle locally. 20s tolerates scheduler
    // latency without masking a real hang — a stuck auth still fails, just later.
    private val connectTimeoutMs = 20_000

    /** What the current server instance will accept as the KI answer. */
    private lateinit var expectedAnswer: String

    /** The prompt text the current server instance issues. */
    private lateinit var promptText: String

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    @Before
    fun defaults() {
        expectedAnswer = totpCode
        promptText = "Verification code: "
    }

    @Test
    fun totpAutoSubmit_answersOtpChallengeWithoutPrompting() {
        startServer()
        var prompterCalled = false
        val connection: SshConnection = SshlibConnection()
        try {
            val hostKey = runBlocking {
                connection.connect(
                    config = passwordConfig(""),
                    connectTimeoutMs = connectTimeoutMs,
                    proxy = null,
                    keyboardInteractivePrompter = { prompterCalled = true; null },
                    totpCodeProvider = { totpCode },
                    confirmOtp = false,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
            }
            assertNotNull("TOFU host key must come back from connect", hostKey)
            assertTrue("must authenticate via the auto-submitted TOTP code", connection.isConnected)
            assertFalse("auto-submit must not surface the UI prompter", prompterCalled)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun prompterAnswersNonSecretChallenge() {
        expectedAnswer = "4"
        promptText = "What is 2+2? "
        startServer()
        var seenPrompt: String? = null
        val connection: SshConnection = SshlibConnection()
        try {
            runBlocking {
                connection.connect(
                    config = passwordConfig("hunter2"),
                    connectTimeoutMs = connectTimeoutMs,
                    proxy = null,
                    keyboardInteractivePrompter = { challenge ->
                        seenPrompt = challenge.prompts.singleOrNull()?.text
                        listOf(expectedAnswer)
                    },
                    totpCodeProvider = null,
                    confirmOtp = false,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
            }
            assertTrue("the prompter's answer must authenticate the session", connection.isConnected)
            assertEquals("the server's prompt must reach the prompter", promptText, seenPrompt)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun confirmOtp_routesThroughPrompterWithPrefilledCode() {
        startServer()
        var seenPrefill: List<String?>? = null
        val connection: SshConnection = SshlibConnection()
        try {
            runBlocking {
                connection.connect(
                    config = passwordConfig(""),
                    connectTimeoutMs = connectTimeoutMs,
                    proxy = null,
                    keyboardInteractivePrompter = { challenge ->
                        seenPrefill = challenge.prefilled
                        listOf(totpCode)
                    },
                    totpCodeProvider = { totpCode },
                    confirmOtp = true,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
            }
            assertTrue(connection.isConnected)
            assertTrue(
                "confirm-OTP must reach the prompter with the code pre-filled",
                seenPrefill?.contains(totpCode) == true,
            )
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The follow-up rule: when the profile's own method fails and the server
     * still offers keyboard-interactive, the engine must try it rather than
     * giving up. That is the shape a second factor arrives in — the first
     * factor answered by a FAILURE that still lists keyboard-interactive, which
     * sshlib's public [org.connectbot.sshlib.AuthResult.Failure] cannot
     * distinguish from a flat rejection — and it is what makes
     * [ConnectionConfig.AuthMethod.Multi] work with no per-chain plumbing.
     *
     * Driven here with a rejected password rather than a rejected key, so the
     * test needs no key material; the code path (primary method returns
     * Failure → KI follow-up) is the same one a chain takes.
     */
    @Test
    fun primaryMethodRejected_stillFallsThroughToKeyboardInteractive() {
        expectedAnswer = "4"
        promptText = "What is 2+2? "
        startServer(offerPassword = true)
        val connection: SshConnection = SshlibConnection()
        try {
            runBlocking {
                connection.connect(
                    config = passwordConfig("wrong-password"),
                    connectTimeoutMs = connectTimeoutMs,
                    proxy = null,
                    keyboardInteractivePrompter = { listOf(expectedAnswer) },
                    totpCodeProvider = null,
                    confirmOtp = false,
                    preConnect = null,
                    trustedHostCaKeys = emptyList(),
                )
            }
            assertTrue(
                "a rejected first factor must not end auth while the server still offers KI",
                connection.isConnected,
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun hostKeyGateRejects_abortsBeforePasswordOrKeyboardInteractiveAuth() {
        val passwordsReceived = java.util.concurrent.CopyOnWriteArrayList<String>()
        val kiChallenges = java.util.concurrent.atomic.AtomicInteger(0)
        val keyFile = Files.createTempFile("haven-sshlib-mitm-hostkey-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                algorithm = "RSA"
                keySize = 2048
                loadKeys(null)
            }
            userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE)
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ ->
                passwordsReceived.add(password)
                true
            }
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(
                    session: ServerSession,
                    username: String,
                    lang: String,
                    subMethods: String,
                ): InteractiveChallenge {
                    kiChallenges.incrementAndGet()
                    return InteractiveChallenge().apply {
                        interactionName = "OTP"
                        interactionInstruction = ""
                        languageTag = ""
                        addPrompt("Verification code: ", false)
                    }
                }
                override fun authenticate(session: ServerSession, username: String, responses: List<String>) = true
            }
        }
        server.start()
        serverPort = server.port

        val connection: SshConnection = SshlibConnection()
        var prompterCalled = false
        try {
            val thrown = org.junit.Assert.assertThrows(sh.haven.core.ssh.SshHostKeyRejectedException::class.java) {
                runBlocking {
                    connection.connect(
                        config = passwordConfig("sshlib-secret-password"),
                        connectTimeoutMs = connectTimeoutMs,
                        proxy = null,
                        keyboardInteractivePrompter = { prompterCalled = true; listOf(totpCode) },
                        totpCodeProvider = { totpCode },
                        confirmOtp = false,
                        preConnect = null,
                        trustedHostCaKeys = emptyList(),
                        hostKeyGate = { entry ->
                            throw sh.haven.core.ssh.SshHostKeyRejectedException(entry, "sshlib MITM host key rejected")
                        },
                    )
                }
            }
            assertEquals("sshlib MITM host key rejected", thrown.message)
            assertTrue(
                "sshlib MUST NOT send password when hostKeyGate rejects during KEX, got: $passwordsReceived",
                passwordsReceived.isEmpty(),
            )
            assertEquals("sshlib MUST NOT start KI when hostKeyGate rejects during KEX", 0, kiChallenges.get())
            assertFalse(prompterCalled)
            assertFalse(connection.isConnected)
        } finally {
            connection.disconnect()
        }
    }

    private fun passwordConfig(password: String) = ConnectionConfig(
        host = "127.0.0.1",
        port = serverPort,
        username = "alice",
        authMethod = ConnectionConfig.AuthMethod.Password(password),
    )

    private fun startServer(offerPassword: Boolean = false) {
        val keyFile = Files.createTempFile("haven-sshlib-ki-hostkey-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            userAuthFactories = if (offerPassword) {
                listOf(UserAuthPasswordFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE)
            } else {
                listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
            }
            // No password is ever accepted: the point is what happens next.
            passwordAuthenticator = RejectAllPasswordAuthenticator.INSTANCE
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(
                    session: ServerSession,
                    username: String,
                    lang: String,
                    subMethods: String,
                ): InteractiveChallenge = InteractiveChallenge().apply {
                    interactionName = "Haven test"
                    interactionInstruction = ""
                    languageTag = ""
                    addPrompt(promptText, false)
                }

                override fun authenticate(
                    session: ServerSession,
                    username: String,
                    responses: List<String>,
                ): Boolean = responses.size == 1 && responses[0] == expectedAnswer
            }
        }
        server.start()
        serverPort = server.port
    }
}
