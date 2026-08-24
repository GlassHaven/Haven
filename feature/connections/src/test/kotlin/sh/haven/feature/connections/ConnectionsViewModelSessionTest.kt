package sh.haven.feature.connections

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.spa.SpaSender
import sh.haven.core.spa.SpaResult
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.db.entities.ConnectionLog
import org.junit.Assert.assertNotNull
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.DecryptedKeys
import sh.haven.core.data.repository.KeyMaterial
import sh.haven.core.data.repository.KeyUnlockDeclinedException
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.et.EtSessionManager
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.mail.MailSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.Transport
import sh.haven.core.ssh.TransportSessionManager

/**
 * A registry entry that only knows how to disconnect — enough for the
 * disconnect/delete paths under test, and the same shape as the real bindings
 * for transports with no live session list.
 */
private fun disconnectable(t: Transport, onDisconnect: (String) -> Unit) =
    object : TransportSessionManager {
        override val transport = t
        override fun removeAllSessionsForProfile(profileId: String) = onDisconnect(profileId)
    }

/**
 * Tests that disconnect() and deleteConnection() clean up ALL session manager types.
 * Regression tests for the bug where local and SMB sessions survived disconnect/delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appContext: Context
    private val connectionLogRepository: ConnectionLogRepository = mockk(relaxed = true)
    private val spaSender: SpaSender = mockk(relaxed = true)
    private lateinit var repository: ConnectionRepository
    private lateinit var portForwardRepository: PortForwardRepository
    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var reticulumSessionManager: ReticulumSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var smbSessionManager: SmbSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var rdpSessionManager: RdpSessionManager
    private lateinit var mailSessionManager: MailSessionManager
    private lateinit var rcloneSessionManager: sh.haven.core.rclone.RcloneSessionManager
    private lateinit var prootManager: ProotManager
    private lateinit var desktopManager: DesktopManager
    private lateinit var sessionManagerRegistry: SessionManagerRegistry
    private lateinit var sshKeyRepository: SshKeyRepository
    private lateinit var preferencesRepository: UserPreferencesRepository
    private lateinit var viewModel: ConnectionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        appContext = mockk(relaxed = true)
        repository = mockk(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }
        portForwardRepository = mockk(relaxed = true)
        sshSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { hasActiveSessions } returns false
        }
        reticulumSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        moshSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        etSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        smbSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        prootManager = mockk(relaxed = true)
        desktopManager = mockk(relaxed = true) {
            every { desktops } returns MutableStateFlow(emptyMap())
        }
        localSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
            every { prootManager } returns this@ConnectionsViewModelSessionTest.prootManager
            every { desktopManager } returns this@ConnectionsViewModelSessionTest.desktopManager
        }
        rdpSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        mailSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        rcloneSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // Since #510 the registry takes contributed transports rather than
        // naming each manager, so the bindings that :app provides in
        // TransportSessionManagerModule are stood up here as thin fakes that
        // delegate to the same mocks — which is what keeps the disconnect
        // assertions below testing real behaviour.
        sessionManagerRegistry = SessionManagerRegistry(
            transports = setOf(
                disconnectable(Transport.SSH) { sshSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.RETICULUM) { reticulumSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.MOSH) { moshSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.ET) { etSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.SMB) { smbSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.LOCAL) { localSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.RDP) { rdpSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.MAIL) { mailSessionManager.removeAllSessionsForProfile(it) },
                disconnectable(Transport.RCLONE) { rcloneSessionManager.removeAllSessionsForProfile(it) },
                // The three serial transports were anonymous mocks here before
                // and nothing asserts them; they stay no-ops.
                disconnectable(Transport.BTSERIAL) {},
                disconnectable(Transport.BLESERIAL) {},
                disconnectable(Transport.USBSERIAL) {},
            ),
            keepAlives = emptySet(),
        )

        sshKeyRepository = mockk(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }

        preferencesRepository = mockk(relaxed = true) {
            every { sessionManager } returns flowOf(mockk(relaxed = true) {
                every { label } returns "None"
            })
            // connectSsh reads both of these with .first(); a relaxed Flow
            // mock never emits, so the connect would hang instead of failing.
            every { verboseLoggingEnabled } returns flowOf(false)
            every { sessionCommandOverride } returns flowOf(null)
        }

        viewModel = ConnectionsViewModel(
            appContext = appContext,
            repository = repository,
            portForwardRepository = portForwardRepository,
            sshSessionManager = sshSessionManager,
            // Relaxed: looksLikeBackgroundRestriction() returns false, so the
            // #495 attribution path stays quiet and these tests keep asserting
            // ordinary session behaviour.
            backgroundDisconnectDetector = mockk(relaxed = true),
            sshSessionAttacher = mockk(relaxed = true),
            reticulumSessionManager = reticulumSessionManager,
            moshSessionManager = moshSessionManager,
            etSessionManager = etSessionManager,
            btSerialSessionManager = mockk(relaxed = true) {
                // init's link-drop observer collects this StateFlow; a bare relaxed
                // mock returns a relaxed `collect` (declared Nothing) → KotlinNothingValueException.
                every { sessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            },
            bleSerialSessionManager = mockk(relaxed = true) {
                every { sessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            },
            usbSerialSessionManager = mockk(relaxed = true) {
                every { sessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            },
            usbBroker = mockk(relaxed = true),
            reticulumTransport = mockk(relaxed = true) {
                every { discoveredDestinations } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            },
            smbSessionManager = smbSessionManager,
            rcloneSessionManager = rcloneSessionManager,
            rcloneClient = mockk(relaxed = true),
            fidoAuthenticator = mockk(relaxed = true),
            localSessionManager = localSessionManager,
            mailSessionManager = mailSessionManager,
            sessionManagerRegistry = sessionManagerRegistry,
            sshKeyRepository = sshKeyRepository,
            totpSecretRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            preferencesRepository = preferencesRepository,
            connectionGroupDao = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            sshIdentityRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
                // No identity assigned in these tests: applyTo is a pass-through
                // so the connect path sees the profile's inline credentials.
                coEvery { applyTo(any()) } answers { firstArg() }
            },
            hostKeyVerifier = mockk(relaxed = true),
            connectionLogRepository = connectionLogRepository,
            tunnelResolver = mockk(relaxed = true),
            portKnocker = mockk(relaxed = true),
            spaSender = spaSender,
            tunnelConfigRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            certRenewalGate = mockk(relaxed = true) {
                every { renewing } returns kotlinx.coroutines.flow.MutableStateFlow(null)
            },
            desktopSessionRegistry = mockk(relaxed = true) {
                every { statuses } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            },
            agentUiCommandBus = mockk(relaxed = true) {
                every { commands } returns kotlinx.coroutines.flow.MutableSharedFlow()
            },
            agentActivityHolder = mockk(relaxed = true) {
                every { activeProfiles } returns MutableStateFlow(emptyMap())
            },
            userMessageBus = sh.haven.core.data.message.UserMessageBus(),
            hostRediscovery = mockk(relaxed = true) {
                coEvery { rediscover(any()) } returns null
            },
            usbipForwarder = mockk(relaxed = true),
            biometricGate = mockk(relaxed = true),
            pendingAuthPromptHolder = mockk(relaxed = true),
            sessionSelectionHolder = mockk(relaxed = true),
            connectionPreflight = mockk(relaxed = true) {
                coEvery { beforeConnect(any()) } answers {
                    sh.haven.core.data.repository.ConnectionPreflight.Result.Proceed(firstArg())
                }
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `disconnect cleans up all session manager types`() {
        viewModel.disconnect("profile1")

        verify { sshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { reticulumSessionManager.removeAllSessionsForProfile("profile1") }
        verify { moshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { etSessionManager.removeAllSessionsForProfile("profile1") }
        verify { smbSessionManager.removeAllSessionsForProfile("profile1") }
        verify { localSessionManager.removeAllSessionsForProfile("profile1") }
        // #363: rclone was missing from the registry, so disconnect left
        // the storage card CONNECTED forever.
        verify { rcloneSessionManager.removeAllSessionsForProfile("profile1") }
    }

    @Test
    fun `disconnect stops VNC server`() {
        viewModel.disconnect("profile1")

        verify { desktopManager.stopAll() }
    }

    @Test
    fun `deleteConnection cleans up all session manager types`() = runTest {
        viewModel.deleteConnection("profile1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { sshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { reticulumSessionManager.removeAllSessionsForProfile("profile1") }
        verify { moshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { etSessionManager.removeAllSessionsForProfile("profile1") }
        verify { smbSessionManager.removeAllSessionsForProfile("profile1") }
        verify { localSessionManager.removeAllSessionsForProfile("profile1") }
        verify { rcloneSessionManager.removeAllSessionsForProfile("profile1") }
    }

    @Test
    fun `deleteConnection stops VNC server and deletes from repository`() = runTest {
        viewModel.deleteConnection("profile1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { desktopManager.stopAll() }
        coVerify { repository.delete("profile1") }
    }

    private fun jumpProfile(authMethods: String) = ConnectionProfile(
        id = "jump", label = "jump", host = "h", username = "u",
        connectionType = "SSH", authMethods = authMethods,
    )

    private fun skKey() = SshKey(
        label = "yk", keyType = "sk-ssh-ed25519@openssh.com",
        privateKeyBytes = ByteArray(0), publicKeyOpenSsh = "", fingerprintSha256 = "",
    )

    // #286: a FIDO ("Any hardware key") jump host has no saved password and no
    // legacy keyId — connectJumpHost authenticates it via the FIDO authenticator,
    // so the pre-check must NOT shadow that with a password prompt.
    @Test
    fun `FIDO jump host with an sk-key enrolled needs no password prompt`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        coEvery { repository.getById("jump") } returns jumpProfile("ANY_HARDWARE_KEY")
        coEvery { sshKeyRepository.getAllDecrypted() } returns listOf(skKey())

        assertNull(viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // Don't over-suppress: "Any hardware key" with no sk-key enrolled has no
    // usable credential, so the prompt IS the fallback path.
    @Test
    fun `Any-hardware-key jump host with no sk-key prompts for password`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        val jp = jumpProfile("ANY_HARDWARE_KEY")
        coEvery { repository.getById("jump") } returns jp
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()

        assertSame(jp, viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // Password-only jump host with nothing saved → prompt (existing behaviour).
    @Test
    fun `password-only jump host with no saved password prompts`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        val jp = jumpProfile("PASSWORD")
        coEvery { repository.getById("jump") } returns jp
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()

        assertSame(jp, viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // #531: an offer-enabled sk-key is auto-offered like any software key now,
    // so it suppresses the prompt even without an explicit "Any hardware key"
    // spec — same contract as the unencrypted-software-key clause.
    @Test
    fun `enrolled sk-key suppresses jump password prompt without a spec`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        coEvery { repository.getById("jump") } returns jumpProfile("")
        coEvery { sshKeyRepository.getAllDecrypted() } returns listOf(skKey())

        assertNull(viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // The Keys-screen "Offer for connections" toggle must keep its meaning for
    // sk-keys: toggled off, the key is not auto-offered, so the prompt stays.
    @Test
    fun `offer-disabled sk-key does not suppress the jump password prompt`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        val jp = jumpProfile("")
        coEvery { repository.getById("jump") } returns jp
        coEvery { sshKeyRepository.getAllDecrypted() } returns
            listOf(skKey().copy(enabledForAuth = false))

        assertSame(jp, viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // #531: with no password, the auto-offer bundle must contain BOTH the
    // software-key pool and the FIDO pool — an offer-enabled sk-key alone
    // used to fall through to (empty) password auth and never be offered.
    @Test
    fun `resolveAuthMethod offers software and sk-keys together`() = runTest {
        // Auth paths read getAllDecryptedDetailed, not getAllDecrypted, so a
        // declined unlock can stop the connect instead of being dropped (#559).
        coEvery { sshKeyRepository.getAllDecryptedDetailed() } returns DecryptedKeys(
            keys = listOf(
                SshKey(
                    label = "laptop", keyType = "ssh-ed25519",
                    privateKeyBytes = ByteArray(64), publicKeyOpenSsh = "", fingerprintSha256 = "",
                ),
                skKey(),
            ),
            declined = emptyList(),
        )
        val profile = ConnectionProfile(
            id = "p", label = "p", host = "h", username = "u", connectionType = "SSH",
        )

        val auth = viewModel.resolveAuthMethod(profile, password = "")

        val multi = auth as ConnectionConfig.AuthMethod.Multi
        val software = multi.methods.filterIsInstance<ConnectionConfig.AuthMethod.PrivateKeys>().single()
        org.junit.Assert.assertEquals(listOf("laptop"), software.keys.map { it.label })
        val fido = multi.methods.filterIsInstance<ConnectionConfig.AuthMethod.FidoKey>().single()
        org.junit.Assert.assertEquals("yk", fido.keyLabel)
        org.junit.Assert.assertTrue(fido.anyOf)
    }

    // A FIDO-only keystore must still resolve to the FIDO pool, not password.
    @Test
    fun `resolveAuthMethod offers a lone sk-key`() = runTest {
        coEvery { sshKeyRepository.getAllDecryptedDetailed() } returns
            DecryptedKeys(keys = listOf(skKey()), declined = emptyList())
        val profile = ConnectionProfile(
            id = "p", label = "p", host = "h", username = "u", connectionType = "SSH",
        )

        val auth = viewModel.resolveAuthMethod(profile, password = "")

        val fido = auth as ConnectionConfig.AuthMethod.FidoKey
        org.junit.Assert.assertEquals("yk", fido.keyLabel)
    }

    // --- #557: a pre-connect gate that fails must not be invisible ---------
    //
    // SPA/knock failures are deliberately non-fatal, so the connect proceeds
    // and dies later as an ordinary read timeout against a port nothing
    // opened. Before this, the only trace was a VERBOSE log line — a reporter
    // chasing exactly this saw a connection log holding the hostname and a
    // socket exception, with nothing about the packet that never left.

    private fun spaProfile() = ConnectionProfile(
        id = "spa-profile", label = "spa", host = "example.invalid", username = "u",
        connectionType = "SSH",
        spaKey = "0123456789abcdef",
        spaAccessSpec = "tcp/22",
        spaPort = 62201,
    )

    @Test
    fun `a failed SPA packet is written to the ordinary connection log`() = runTest {
        coEvery { spaSender.send(any(), any()) } returns
            SpaResult(packetLen = 0, totalDurationMs = 5021, error = java.io.IOException("network unreachable"))

        val hook = viewModel.buildKnockHook(spaProfile(), verboseLogger = null)
        assertNotNull(hook)
        hook!!.invoke()

        coVerify {
            connectionLogRepository.logEvent(
                profileId = "spa-profile",
                status = ConnectionLog.Status.FAILED,
                details = match { it != null && it.startsWith("[spa]") && it.contains("failed") },
            )
        }
    }

    // The other half of the property: success stays quiet. A FAILED row per
    // successful connect would be worse than the silence it replaced.
    @Test
    fun `a successful SPA packet writes no connection-log row`() = runTest {
        coEvery { spaSender.send(any(), any()) } returns
            SpaResult(packetLen = 152, totalDurationMs = 1043, error = null)

        viewModel.buildKnockHook(spaProfile(), verboseLogger = null)!!.invoke()

        coVerify(exactly = 0) {
            connectionLogRepository.logEvent(any(), ConnectionLog.Status.FAILED, any(), any(), any())
        }
    }

    // --- #559: a declined key unlock must stop the connect ---------------
    //
    // The report: with a biometric requirement set, pressing back on the
    // prompt still connected. resolveAuthMethod is the last thing that runs
    // before a socket is opened, so "throws here" is the same property as
    // "no connection was attempted", and it is the one these three pin.

    private fun keyProfile(keyId: String?) = ConnectionProfile(
        id = "bio-profile", label = "bio", host = "example.invalid", username = "u",
        connectionType = "SSH",
        keyId = keyId,
    )

    @Test
    fun `declining the prompt for a profile's pinned key aborts instead of falling through`() = runTest {
        coEvery { sshKeyRepository.fetchKeyMaterial("k-bio") } returns
            KeyMaterial.Declined("Authentication was declined for key \"work laptop\".")

        val thrown = try {
            viewModel.resolveAuthMethod(keyProfile("k-bio"), password = "hunter2")
            null
        } catch (e: KeyUnlockDeclinedException) {
            e
        }

        // Before the fix this returned AuthMethod.Password("hunter2") — the
        // declined key was dropped and the connect went ahead with whatever
        // else the profile had.
        assertNotNull("expected the connect to be refused", thrown)
        assertTrue(thrown!!.reason, thrown.reason.contains("work laptop"))
    }

    @Test
    fun `declining any key in the try-every-key path aborts too`() = runTest {
        // No pinned key and no password, so resolveAuthMethod walks the whole
        // keyring. A decline there used to drop just that key and carry on
        // with the next one, which turned "no" into "use something else".
        coEvery { sshKeyRepository.getAllDecryptedDetailed() } returns DecryptedKeys(
            keys = listOf(
                SshKey(
                    id = "k-plain", label = "other", keyType = "ssh-ed25519",
                    privateKeyBytes = byteArrayOf(1), publicKeyOpenSsh = "ssh-ed25519 A",
                    fingerprintSha256 = "SHA256:other",
                ),
            ),
            declined = listOf(KeyMaterial.Declined("Authentication was declined for key \"work laptop\".")),
        )

        val thrown = try {
            viewModel.resolveAuthMethod(keyProfile(null), password = "")
            null
        } catch (e: KeyUnlockDeclinedException) {
            e
        }

        assertNotNull("expected the connect to be refused", thrown)
    }

    @Test
    fun `a keyring with nothing declined still resolves normally`() = runTest {
        // The other half: this must not turn every connect into a refusal.
        coEvery { sshKeyRepository.getAllDecryptedDetailed() } returns
            DecryptedKeys(keys = emptyList(), declined = emptyList())

        val method = viewModel.resolveAuthMethod(keyProfile(null), password = "hunter2")

        assertTrue(method.toString(), method is ConnectionConfig.AuthMethod.Password)
    }

    @Test
    fun `a declined key unlock reports the decline and does NOT offer the password fallback`() = runTest {
        coEvery { sshKeyRepository.fetchKeyMaterial("k-bio") } returns
            KeyMaterial.Declined("Authentication was declined for key \"work laptop\".")

        // remoteCommand set so the connect skips session-manager discovery and
        // goes straight at auth resolution, which is what this test is about.
        val profile = keyProfile("k-bio").copy(remoteCommand = "true")
        viewModel.connectSsh(profile, password = "", keyOnly = true)

        // Auth resolution runs inside withContext(Dispatchers.IO), which the
        // test scheduler does not own, so advanceUntilIdle alone returns before
        // the failure lands. Pump both until the error appears or we give up.
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.error.value == null && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }

        // The message says the user declined...
        assertTrue(
            "error was: ${viewModel.error.value}",
            viewModel.error.value.orEmpty().contains("declined"),
        )
        // ...and Haven does not answer a refusal by suggesting another way in.
        // The word "authentication" in the message makes the ordinary auth
        // classifier want to raise the password prompt here, which is why the
        // declined branch has to be tested rather than assumed.
        assertNull(viewModel.passwordFallback.value)
    }

    // The same property for the sibling connect paths. connectSsh got the
    // declined branch first; Mosh, Eternal Terminal and the jump-host
    // failure handler each have their own copy of the "authentication"
    // classifier, and each of these fails with the password prompt raised
    // if its declined branch is removed.

    /** Pump until the connect's failure lands or the deadline passes. */
    private fun awaitError() {
        val deadline = System.currentTimeMillis() + 10_000
        while (viewModel.error.value == null && System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
    }

    @Test
    fun `a declined key unlock on the Mosh path does NOT offer the password fallback`() = runTest {
        coEvery { sshKeyRepository.fetchKeyMaterial("k-bio") } returns
            KeyMaterial.Declined("Authentication was declined for key \"work laptop\".")

        viewModel.connectMosh(
            keyProfile("k-bio").copy(useMosh = true),
            password = "",
            keyOnly = true,
        )
        awaitError()

        assertTrue(
            "error was: ${viewModel.error.value}",
            viewModel.error.value.orEmpty().contains("declined"),
        )
        assertNull(viewModel.passwordFallback.value)
    }

    @Test
    fun `a declined key unlock on the Eternal Terminal path does NOT offer the password fallback`() = runTest {
        coEvery { sshKeyRepository.fetchKeyMaterial("k-bio") } returns
            KeyMaterial.Declined("Authentication was declined for key \"work laptop\".")

        viewModel.connectEternalTerminal(
            keyProfile("k-bio").copy(useEternalTerminal = true),
            password = "",
            keyOnly = true,
        )
        awaitError()

        assertTrue(
            "error was: ${viewModel.error.value}",
            viewModel.error.value.orEmpty().contains("declined"),
        )
        assertNull(viewModel.passwordFallback.value)
    }

    @Test
    fun `a declined key unlock on a jump host does NOT re-open the password prompt`() = runTest {
        // The prompt path needs the jump profile to resolve; stub it so the
        // classifier below the declined branch WOULD raise the prompt if it
        // were reached — otherwise this test could pass by lookup failure.
        coEvery { repository.getById("jump") } returns jumpProfile("")

        viewModel.handleTunnelJumpFailure(
            KeyUnlockDeclinedException("Authentication was declined for key \"work laptop\"."),
            dependentProfile = keyProfile(null).copy(id = "vnc-dep", connectionType = "VNC"),
            sshProfileId = "jump",
        )

        assertTrue(
            "error was: ${viewModel.error.value}",
            viewModel.error.value.orEmpty().contains("declined"),
        )
        assertNull(viewModel.passwordFallback.value)
        assertNull(viewModel.pendingTunnelDependent.value)
    }

    /**
     * #582: the remembered-password save runs AFTER the server has authenticated
     * and the session channel is open. When it threw, the exception unwound
     * connectSsh, which reported a failed connection and disconnected a session
     * the server had already recorded as `session opened` — the user saw a
     * failure and got no terminal, and the server was left holding an orphan.
     *
     * Remembering a password is a convenience and must not be able to cancel a
     * login that already worked.
     */
    @Test
    fun `a failing credential save does not escape and cancel the session`() = runTest {
        val profile = keyProfile(null)
        coEvery { repository.save(any()) } throws
            IllegalStateException("Keystore cannot load the key with ID: haven_credential_master")

        // Must not throw. Before the fix this propagated out of connectSsh.
        viewModel.persistRememberedPassword(
            profile = profile,
            password = "hunter2",
            rememberPassword = true,
            multiUserProfile = false,
        )

        // And the user is told, rather than the failure passing silently.
        assertNotNull("the failure must surface", viewModel.error.value)
    }
}
