package sh.haven.core.ssh

import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * End-to-end (in-process) verification of the OATH-TOTP auto-fill path
 * (#178): a real Apache MINA sshd issues a keyboard-interactive
 * "Verification code:" challenge, and [SshClient] answers it from the
 * supplied `totpCodeProvider` — driving the full JSch keyboard-interactive
 * round-trip, not just the bridge in isolation.
 *
 * The server accepts a fixed code (424242) and the test's provider returns
 * that same value, decoupling the assertion from real wall-clock TOTP
 * timing (the generator itself is covered by `TotpTest`).
 *
 * MINA sshd runs on the host JVM only — same constraint as
 * [SshClientTofuTest] — so this lives in `src/test`.
 */
class SshClientTotpAuthTest {

    private lateinit var server: SshServer
    private var serverPort: Int = 0
    private val expectedCode = "424242"

    @Before
    fun startServer() {
        server = buildServer()
        server.start()
        serverPort = server.port
    }

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(true)
    }

    @Test
    fun totpAutoSubmit_answersOtpChallengeWithoutPrompting() {
        var prompterCalled = false
        val client = SshClient()
        try {
            val hostKey = runBlocking {
                client.connect(
                    config = ConnectionConfig(
                        host = "127.0.0.1",
                        port = serverPort,
                        username = "alice",
                        authMethod = ConnectionConfig.AuthMethod.Password(""),
                    ),
                    connectTimeoutMs = 15_000,
                    keyboardInteractivePrompter = { prompterCalled = true; null },
                    totpCodeProvider = { expectedCode },
                    confirmOtp = false,
                )
            }
            assertNotNull(hostKey)
            assertTrue("session must connect via auto-submitted TOTP code", client.isConnected)
            assertFalse("auto-submit must not surface the UI prompter", prompterCalled)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun confirmOtp_routesThroughPrompterWithPrefilledCode() {
        var seenPrefill: List<String?>? = null
        val client = SshClient()
        try {
            runBlocking {
                client.connect(
                    config = ConnectionConfig(
                        host = "127.0.0.1",
                        port = serverPort,
                        username = "alice",
                        authMethod = ConnectionConfig.AuthMethod.Password(""),
                    ),
                    connectTimeoutMs = 15_000,
                    keyboardInteractivePrompter = { challenge ->
                        seenPrefill = challenge.prefilled
                        // User confirms the pre-filled code.
                        listOf(expectedCode)
                    },
                    totpCodeProvider = { expectedCode },
                    confirmOtp = true,
                )
            }
            assertTrue(client.isConnected)
            assertNotNull("confirm-OTP must reach the prompter", seenPrefill)
            assertTrue(
                "the generated code must be pre-filled for confirm",
                seenPrefill?.contains(expectedCode) == true,
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun threeFactorOneTimePasswordAndPassword_blankPasswordPromptsOnceAndSucceeds() {
        val threeFactor = buildThreeFactorPamServer(expectedOtp = expectedCode, expectedPassword = "s3cr3t!")
        threeFactor.start()
        val seenPrompts = mutableListOf<String>()
        val client = SshClient()
        try {
            runBlocking {
                client.connect(
                    config = ConnectionConfig(
                        host = "127.0.0.1",
                        port = threeFactor.port,
                        username = "alice",
                        authMethod = ConnectionConfig.AuthMethod.Multi(
                            listOf(
                                testEd25519AuthMethod(),
                                ConnectionConfig.AuthMethod.Password(""),
                            ),
                        ),
                    ),
                    connectTimeoutMs = 15_000,
                    keyboardInteractivePrompter = { challenge ->
                        seenPrompts += challenge.prompts.map { it.text }
                        listOf("s3cr3t!")
                    },
                    totpCodeProvider = { expectedCode },
                    confirmOtp = false,
                )
            }
            assertTrue("3-factor connect must succeed when user supplies missing password", client.isConnected)
            assertTrue("only the Password: round should prompt (OTP was auto-filled)", seenPrompts == listOf("Password: "))
        } finally {
            client.disconnect()
            threeFactor.stop(true)
        }
    }

    @Test
    fun threeFactorOneTimePasswordAndSavedPassword_autoCompletesWithoutPrompting() {
        val threeFactor = buildThreeFactorPamServer(expectedOtp = expectedCode, expectedPassword = "s3cr3t!")
        threeFactor.start()
        var prompterCalled = false
        val client = SshClient()
        try {
            runBlocking {
                client.connect(
                    config = ConnectionConfig(
                        host = "127.0.0.1",
                        port = threeFactor.port,
                        username = "alice",
                        authMethod = ConnectionConfig.AuthMethod.Multi(
                            listOf(
                                testEd25519AuthMethod(),
                                ConnectionConfig.AuthMethod.Password("s3cr3t!"),
                            ),
                        ),
                    ),
                    connectTimeoutMs = 15_000,
                    keyboardInteractivePrompter = { prompterCalled = true; null },
                    totpCodeProvider = { expectedCode },
                    confirmOtp = false,
                )
            }
            assertTrue("3-factor connect with saved password + TOTP + key must succeed", client.isConnected)
            assertFalse("all 3 factors stored -> no UI prompt expected", prompterCalled)
        } finally {
            client.disconnect()
            threeFactor.stop(true)
        }
    }

    private fun testEd25519AuthMethod(): ConnectionConfig.AuthMethod.PrivateKey {
        val jsch = com.jcraft.jsch.JSch()
        val kp = com.jcraft.jsch.KeyPair.genKeyPair(jsch, com.jcraft.jsch.KeyPair.RSA, 2048)
        val out = java.io.ByteArrayOutputStream()
        kp.writePrivateKey(out)
        kp.dispose()
        return ConnectionConfig.AuthMethod.PrivateKey(keyBytes = out.toByteArray())
    }

    private fun buildThreeFactorPamServer(
        expectedOtp: String,
        expectedPassword: String,
    ): SshServer {
        val keyFile = Files.createTempFile("haven-3fa-hostkey-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }
        return SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            // Require all three SSH factors in sequence:
            // 1. publickey, 2. keyboard-interactive ("One-time password: "), 3. password.
            properties["auth-methods"] = "publickey,keyboard-interactive,password"
            publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, _, _ -> true }
            passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { _, password, _ ->
                password == expectedPassword
            }
            userAuthFactories = listOf(
                org.apache.sshd.server.auth.pubkey.UserAuthPublicKeyFactory.INSTANCE,
                UserAuthKeyboardInteractiveFactory.INSTANCE,
                org.apache.sshd.server.auth.password.UserAuthPasswordFactory.INSTANCE,
            )
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(
                    session: ServerSession,
                    username: String,
                    lang: String,
                    subMethods: String,
                ): InteractiveChallenge {
                    return InteractiveChallenge().apply {
                        interactionName = "PAM OATH"
                        interactionInstruction = ""
                        languageTag = ""
                        addPrompt("One-time password: ", false)
                    }
                }

                override fun authenticate(
                    session: ServerSession,
                    username: String,
                    responses: List<String>,
                ): Boolean = responses.size == 1 && responses[0] == expectedOtp
            }
        }
    }

    private fun buildServer(): SshServer {
        val keyFile = Files.createTempFile("haven-totp-hostkey-", ".ser").also {
            Files.deleteIfExists(it)
            it.toFile().deleteOnExit()
        }
        return SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile).apply {
                algorithm = "RSA"
                keySize = 2048
            }
            // Offer ONLY keyboard-interactive so JSch uses it unambiguously.
            userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
            keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
                override fun generateChallenge(
                    session: ServerSession,
                    username: String,
                    lang: String,
                    subMethods: String,
                ): InteractiveChallenge {
                    val challenge = InteractiveChallenge()
                    challenge.interactionName = "TOTP"
                    challenge.interactionInstruction = ""
                    challenge.languageTag = ""
                    challenge.addPrompt("Verification code: ", false)
                    return challenge
                }

                override fun authenticate(
                    session: ServerSession,
                    username: String,
                    responses: List<String>,
                ): Boolean = responses.size == 1 && responses[0] == expectedCode
            }
        }
    }
}
