package sh.haven.feature.terminal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

class TerminalViewModelTest {

    // The ViewModel's init launches eight `sessions.collect { syncSessions() }`
    // collectors. Without a pinned Main dispatcher viewModelScope falls back
    // to EmptyCoroutineContext and they run on Dispatchers.Default — real
    // threads, parallel with the test body — where a reconcile to zero tabs
    // calls resetModifiers() and drops the Ctrl/Alt locks a test just set.
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sessionManager: SshSessionManager
    private lateinit var reticulumSessionManager: ReticulumSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager
    private lateinit var bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager
    private lateinit var bleSerialSessions:
        MutableStateFlow<Map<String, sh.haven.core.bleserial.BleSerialSessionManager.SessionState>>
    private lateinit var usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager
    private lateinit var sshEmulatorOwner: SshTerminalEmulatorOwner
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sessionManager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
        reticulumSessionManager = mockk<ReticulumSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        moshSessionManager = mockk<MoshSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        etSessionManager = mockk<EtSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        localSessionManager = mockk<LocalSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        btSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // #465: stubbed ONCE here, then driven by mutating the flow. The
        // ViewModel's init launches collectors that read these mocks, so a
        // later `every { sessions } ...` would put MockK into recording mode
        // while another thread is invoking the same mock — which throws
        // MockKException rather than failing an assertion, and only when the
        // collectors happen to be live (CI, loaded runner).
        bleSerialSessions = MutableStateFlow(emptyMap())
        bleSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns bleSerialSessions
        }
        usbSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // No connect-time bundle in unit tests (the real emulator needs JNI),
        // so the SSH adopt branch skips — matching the old isReadyForTerminal skip.
        sshEmulatorOwner = mockk(relaxed = true) {
            every { bundleFor(any()) } returns null
        }
        viewModel = TerminalViewModel(
            mockk(relaxed = true),
            sessionManager,
            mockk(relaxed = true), // SshSessionAttacher
            reticulumSessionManager,
            moshSessionManager,
            etSessionManager,
            btSerialSessionManager,
            bleSerialSessionManager,
            usbSerialSessionManager,
            mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            localSessionManager,
            mockk(relaxed = true), // HostKeyVerifier
            mockk(relaxed = true), // FidoAuthenticator
            mockk(relaxed = true), // UserPreferencesRepository
            mockk(relaxed = true), // ConnectionRepository
            mockk(relaxed = true), // TunnelResolver
            sh.haven.core.data.agent.AgentUiCommandBus(),
            sh.haven.core.data.message.UserMessageBus(),
            mockk(relaxed = true),
            sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            sshEmulatorOwner,
            mockk(relaxed = true), // BarcodeDecoder
            mockk(relaxed = true), // TextRecognizer
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initially has no tabs`() {
        assertEquals(0, viewModel.tabs.value.size)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `syncSessions with no sessions produces no tabs`() {
        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTING sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", client)
        // Status is CONNECTING, no shell channel

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTED sessions without shell channel`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        sessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        // No shell channel attached

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `selectTab with no tabs is no-op`() {
        viewModel.selectTab(2)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `closeTab removes from session manager`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        viewModel.closeTab(sessionId)

        assertEquals(null, sessionManager.getSession(sessionId))
    }

    @Test
    fun `closeTab tears down a BLE-serial session`() {
        // Regression: removeTabAndSync only knew SSH/mosh/et/local/reticulum, so a
        // serial session fell through to reticulum (no-op), stayed alive, and
        // syncSessions rebuilt its tab — the tab wouldn't close.
        val sessionId = "ble-1"
        // Drive the already-stubbed flow rather than re-stubbing the mock (#465).
        bleSerialSessions.value = mapOf(sessionId to mockk(relaxed = true))

        viewModel.closeTab(sessionId)

        verify { bleSerialSessionManager.removeSession(sessionId) }
    }

    @Test
    fun `closeSession removes all sessions for profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", c1)
        sessionManager.registerSession("profile1", "Server", c2)

        viewModel.closeSession("profile1")

        assertEquals(0, sessionManager.getSessionsForProfile("profile1").size)
    }

    @Test
    fun `selectTabByProfileId with no matching tab is no-op`() {
        viewModel.selectTabByProfileId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `selectTabBySessionId with no matching tab is no-op`() {
        viewModel.selectTabBySessionId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    // The toolbar dispatches its nav keys by key code, not bytes, so a tapped
    // Ctrl only reaches them through this mask. It was hardcoded to 0, which is
    // why Ctrl+End sent a bare End.
    @Test
    fun `a tapped Ctrl reaches the toolbar's own keys as the vterm mask`() {
        assertEquals(0, viewModel.toolbarModifierMask())

        viewModel.toggleCtrl()
        assertEquals(4, viewModel.toolbarModifierMask())

        viewModel.toggleAlt()
        assertEquals(6, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a one-shot Ctrl is spent by the keystroke that used it`() {
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()

        assertEquals(false, viewModel.ctrlActive.value)
        assertEquals(0, viewModel.toolbarModifierMask())
    }

    // #522, round two: the requester's follow-up settled the lock's meaning —
    // locked (blue) means every keypress carries Ctrl until the user taps it
    // off. No keystroke budget: v5.87.8's release-after-two was both broken in
    // practice (a second consume site spent the modifier after ONE press) and
    // not what the requester wanted once they had it under their fingers.
    @Test
    fun `a second Ctrl tap locks it, and it survives one keystroke`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)

        viewModel.clearStickyModifiers()

        assertEquals("a lock is not spent by one keystroke", true, viewModel.ctrlActive.value)
        assertEquals(4, viewModel.toolbarModifierMask())
    }

    @Test
    fun `a locked Ctrl survives any number of keystrokes`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        repeat(5) { viewModel.clearStickyModifiers() }

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)
        assertEquals(4, viewModel.toolbarModifierMask())
    }

    // #522, round three: the lock outlived the sessions it was locked for.
    // The requester's flow — lock Ctrl, C C to leave Claude Code, D for
    // Ctrl+D to exit the shell — ends the last session with the lock still
    // on, and the next connection started with Ctrl pre-locked. The lock
    // now dies with the last tab. (Deliberately NOT on per-tab close: the
    // toolbar state is shared and a surviving tab may be mid-use of it.)
    @Test
    fun `a locked Ctrl does not survive the last session ending`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleAlt()
        viewModel.toggleAlt()
        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.altLocked.value)

        // Every session manager is empty, so this reconciles to zero tabs —
        // the state right after Ctrl+D closed the final session.
        runBlocking { viewModel.syncSessions() }

        assertEquals(false, viewModel.ctrlLocked.value)
        assertEquals(false, viewModel.ctrlActive.value)
        assertEquals(false, viewModel.altLocked.value)
        assertEquals(false, viewModel.altActive.value)
        assertEquals(0, viewModel.toolbarModifierMask())
    }

    /**
     * Pins the mechanism behind the modifier-lock flake (five CI sightings, a
     * different family member each time).
     *
     * The ViewModel's init starts eight `sessions.collect { syncSessions() }`
     * collectors and every mocked flow replays `emptyMap()`, so construction
     * queues eight reconciles to zero tabs — each of which calls
     * resetModifiers() and drops the locks. Pinned to the test dispatcher they
     * are inert; running them on purpose here shows exactly what used to land
     * at a random moment on a loaded runner and fail the assertion at the top
     * of the family.
     */
    @Test
    fun `a queued init reconcile is what used to wipe a lock mid-test`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        assertEquals("locked before the stray reconcile", true, viewModel.ctrlLocked.value)

        testDispatcher.scheduler.runCurrent()

        assertEquals(
            "the init collectors reconcile to zero tabs and clear the lock",
            false,
            viewModel.ctrlLocked.value,
        )
    }

    @Test
    fun `a third Ctrl tap unlocks it immediately`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        assertEquals(false, viewModel.ctrlLocked.value)
        assertEquals(false, viewModel.ctrlActive.value)
    }

    @Test
    fun `Ctrl can be locked again after a manual unlock`() {
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.toggleCtrl()

        viewModel.toggleCtrl()
        viewModel.toggleCtrl()
        viewModel.clearStickyModifiers()

        assertEquals(true, viewModel.ctrlLocked.value)
        assertEquals(true, viewModel.ctrlActive.value)
    }

    @Test
    fun `Alt locks independently of Ctrl`() {
        viewModel.toggleAlt()
        viewModel.toggleAlt()
        viewModel.toggleCtrl()

        viewModel.clearStickyModifiers()

        assertEquals("the one-shot Ctrl is spent", false, viewModel.ctrlActive.value)
        assertEquals("the locked Alt is not", true, viewModel.altActive.value)
        assertEquals(2, viewModel.toolbarModifierMask())
    }
}
