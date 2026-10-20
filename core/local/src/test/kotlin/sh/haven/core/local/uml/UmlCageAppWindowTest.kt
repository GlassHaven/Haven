package sh.haven.core.local.uml

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import sh.haven.core.local.DesktopManager
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Unit and integration-simulation coverage for UML-backed single-app cage
 * windows ([UmlDesktopManager] cage lifecycle + recipe shell validation).
 */
class UmlCageAppWindowTest {

    private lateinit var shareDir: File
    private val rfbServers = mutableListOf<ServerSocket>()

    @Before
    fun setUp() {
        shareDir = Files.createTempDirectory("uml-cage-test").toFile()
    }

    @After
    fun tearDown() {
        rfbServers.forEach { runCatching { it.close() } }
        rfbServers.clear()
        shareDir.deleteRecursively()
    }

    /** Start a local loopback server that speaks the 4-byte `"RFB "` prefix on connect. */
    private fun startMockRfbServer(): Int {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        rfbServers.add(server)
        thread(isDaemon = true, name = "mock-rfb-${server.localPort}") {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    client.getOutputStream().write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                    client.close()
                } catch (_: Exception) {
                    break
                }
            }
        }
        return server.localPort
    }

    /**
     * Build a mock [UmlGuestManager] whose headless console responds to recipe
     * commands like the real Alpine UML guest.
     */
    private fun simulatedGuest(
        sentCommands: MutableList<String> = CopyOnWriteArrayList(),
        onRecipeCommand: (cmd: String, emit: (String) -> Unit) -> Unit,
    ): UmlGuestManager {
        val guest = mockk<UmlGuestManager>(relaxed = true)
        val sessionsFlow = MutableStateFlow<Map<String, UmlGuestManager.SessionState>>(emptyMap())
        var nextId = 1
        var consoleCallback: ((ByteArray, Int, Int) -> Unit)? = null

        fun emitToConsole(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            consoleCallback?.invoke(bytes, 0, bytes.size)
        }

        every { guest.isAvailable() } returns true
        every { guest.guestShareDir } returns shareDir
        every { guest.sessions } returns sessionsFlow
        every { guest.registerSession(any(), any(), any(), any()) } answers {
            val sid = "uml-sess-${nextId++}"
            val state = UmlGuestManager.SessionState(
                sessionId = sid,
                profileId = firstArg(),
                label = secondArg(),
                status = UmlGuestManager.SessionState.Status.CONNECTING,
                extraKernelArgs = thirdArg(),
                extraEnv = arg(3),
            )
            sessionsFlow.value = sessionsFlow.value + (sid to state)
            sid
        }
        every { guest.connectSession(any()) } answers {
            val sid = firstArg<String>()
            sessionsFlow.value[sid]?.let { st ->
                sessionsFlow.value = sessionsFlow.value + (sid to st.copy(
                    status = UmlGuestManager.SessionState.Status.CONNECTED,
                ))
            }
        }
        every { guest.getActiveSession(any()) } returns mockk(relaxed = true)
        every { guest.startHeadlessShell(any(), any()) } answers {
            val cb = secondArg<(ByteArray, Int, Int) -> Unit>()
            consoleCallback = cb
            emitToConsole("Linux version 6.18.0-uml\r\numl:~# ")
        }
        every { guest.sendInput(any(), any()) } answers {
            val line = secondArg<String>()
            sentCommands.add(line)
            emitToConsole(line)
            onRecipeCommand(line, ::emitToConsole)
        }
        every { guest.closeGuest(any()) } answers {
            val sid = firstArg<String>()
            sessionsFlow.value[sid]?.let { st ->
                sessionsFlow.value = sessionsFlow.value + (sid to st.copy(
                    status = UmlGuestManager.SessionState.Status.DISCONNECTED,
                ))
            }
        }
        return guest
    }

    @Test
    fun recipeScriptPassesPosixShSyntaxCheck() {
        val proc = ProcessBuilder("sh", "-n")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.use { it.write(UmlDesktopManager.RECIPE_SCRIPT.toByteArray(Charsets.UTF_8)) }
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals("RECIPE_SCRIPT failed sh -n:\n$output", 0, code)
    }

    @Test
    fun generatedKioskRunAndConfigPassValidation() {
        val runScript = UmlDesktopManager.buildCageRunScript(slot = 2, command = "galculator")
        val proc = ProcessBuilder("sh", "-n")
            .redirectErrorStream(true)
            .start()
        proc.outputStream.use { it.write(runScript.toByteArray(Charsets.UTF_8)) }
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals("buildCageRunScript failed sh -n:\n$output", 0, proc.waitFor())
        assertTrue(runScript.contains("galculator"))
        assertTrue(runScript.contains("/tmp/wr-cage-2/sway-ipc"))
        assertTrue(runScript.contains("swaymsg -s"))

        val singleCfg = UmlDesktopManager.buildCageSwayConfig(
            slot = 2,
            outW = 720,
            outH = 1280,
            scaleStr = "1.5",
            multiWindow = false,
            swayRules = emptyList(),
        )
        assertTrue(singleCfg.contains("output HEADLESS-1 mode 720x1280@60Hz"))
        assertTrue(singleCfg.contains("output HEADLESS-1 scale 1.5"))
        assertTrue(singleCfg.contains("fullscreen enable"))
        assertTrue(singleCfg.contains("exec sh /host/haven-kiosk-2.run"))

        val multiCfg = UmlDesktopManager.buildCageSwayConfig(
            slot = 3,
            outW = 1280,
            outH = 720,
            scaleStr = "1",
            multiWindow = true,
            swayRules = listOf("for_window [title=\"^Qmmp$\"] move position 20 20\ninjected"),
        )
        assertTrue(multiCfg.contains("floating enable"))
        assertFalse(multiCfg.contains("fullscreen enable"))
        assertTrue(multiCfg.contains("for_window [title=\"^Qmmp$\"] move position 20 20 injected"))
    }

    @Test
    fun extractPrimaryBinaryHandlesSimpleAndWrappedCommands() {
        assertEquals("foot", UmlDesktopManager.extractPrimaryBinary("foot"))
        assertEquals("imv", UmlDesktopManager.extractPrimaryBinary("imv /root/board.png"))
        assertEquals("qmmp", UmlDesktopManager.extractPrimaryBinary("env QT_QPA_PLATFORM=xcb qmmp"))
        assertEquals("synaptic", UmlDesktopManager.extractPrimaryBinary("fakeroot-tcp synaptic"))
        assertEquals("/usr/bin/xterm", UmlDesktopManager.extractPrimaryBinary("FOO=1 BAR=two /usr/bin/xterm -e sh"))
        assertNull(UmlDesktopManager.extractPrimaryBinary("echo hi | foot"))
        assertNull(UmlDesktopManager.extractPrimaryBinary("foot; swaymsg exit"))
    }

    @Test
    fun startCageWindowBootsGuestWithCagePortPoolAndStartsSlot() = runBlocking {
        val port1 = startMockRfbServer()
        val port2 = startMockRfbServer()
        val sent = CopyOnWriteArrayList<String>()
        val guest = simulatedGuest(sent) { line, emit ->
            when {
                line.contains("install wayland") -> {
                    File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
                    emit("HDESKTOP:done\r\numl:~# ")
                }
                line.contains("cage-start 1 ") -> {
                    emit("HCAGE:1:started\r\numl:~# ")
                }
            }
        }
        val dm = mockk<DesktopManager>(relaxed = true)
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = dm,
            cagePortAllocator = { listOf(port1, port2) },
        )

        val session = manager.startCageWindow(
            command = "galculator",
            outW = 720,
            outH = 1280,
            scale = 1.5f,
            multiWindow = false,
            swayRules = emptyList(),
            timeoutMs = 5_000,
        )

        assertEquals(DesktopManager.DesktopState.RUNNING, session.state)
        assertEquals(port1, session.vncPort)
        assertTrue(File(shareDir, "haven-kiosk-1.config").isFile)
        assertTrue(File(shareDir, "haven-kiosk-1.run").isFile)

        val envSlot = slot<List<String>>()
        verify(exactly = 1) { guest.registerSession(any(), any(), any(), capture(envSlot)) }
        val tfwd = envSlot.captured.firstOrNull { it.startsWith("PASST_TFWD=") }.orEmpty()
        assertTrue("PASST_TFWD should include cage port1 ($port1): $tfwd", tfwd.contains(port1.toString()))
        assertTrue("PASST_TFWD should include cage port2 ($port2): $tfwd", tfwd.contains(port2.toString()))
    }

    @Test
    fun secondCageWindowReusesWarmGuestWithoutRebooting() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val port1 = startMockRfbServer()
        val port2 = startMockRfbServer()
        val sent = CopyOnWriteArrayList<String>()
        val guest = simulatedGuest(sent) { line, emit ->
            when {
                line.contains("cage-start 1 ") -> emit("HCAGE:1:started\r\numl:~# ")
                line.contains("cage-start 2 ") -> emit("HCAGE:2:started\r\numl:~# ")
                line.contains("cage-stop 1") -> emit("HCAGE:1:stopped\r\numl:~# ")
            }
        }
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { listOf(port1, port2) },
        )

        val s1 = manager.startCageWindow("foot", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        val s2 = manager.startCageWindow("imv /host/a.png", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)

        assertEquals(DesktopManager.DesktopState.RUNNING, s1.state)
        assertEquals(DesktopManager.DesktopState.RUNNING, s2.state)
        assertNotEquals(s1.vncPort, s2.vncPort)
        // Warm reuse: only ONE kernel boot for both cage windows!
        verify(exactly = 1) { guest.registerSession(any(), any(), any(), any()) }
        verify(exactly = 1) { guest.connectSession(any()) }

        // Stopping s1 and s2 keeps the guest warm so a 3rd launch still doesn't reboot.
        manager.stopCageWindow(s1.sessionId)
        manager.stopCageWindow(s2.sessionId)
        verify(exactly = 0) { guest.closeGuest(any()) }

        val s3 = manager.startCageWindow("foot", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        assertEquals(DesktopManager.DesktopState.RUNNING, s3.state)
        verify(exactly = 1) { guest.registerSession(any(), any(), any(), any()) }
    }

    @Test
    fun liveScaleAndResolutionSendSwaymsgCommandsToSlot() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val port1 = startMockRfbServer()
        val sent = CopyOnWriteArrayList<String>()
        val guest = simulatedGuest(sent) { line, emit ->
            if (line.contains("cage-start 1 ")) emit("HCAGE:1:started\r\numl:~# ")
        }
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { listOf(port1) },
        )

        val s1 = manager.startCageWindow("foot", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        manager.setCageScale(s1.sessionId, 1.5f)
        manager.setCageResolution(s1.sessionId, 720, 1280)

        assertTrue("Expected cage-scale 1 1.5 in $sent", sent.any { it.contains("cage-scale 1 1.5") })
        assertTrue("Expected cage-mode 1 720 1280 in $sent", sent.any { it.contains("cage-mode 1 720 1280") })
    }

    @Test
    fun cageFailureSurfacesErrorAndReadsSlotLog() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val port1 = startMockRfbServer()
        val guest = simulatedGuest { line, emit ->
            if (line.contains("cage-start 1 ")) {
                File(shareDir, "haven-kiosk-1.log").writeText("apk: unable to select package missing_app\n")
                emit("HCAGE:1:fail command 'missing_app' not found\r\numl:~# ")
            }
        }
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { listOf(port1) },
        )

        val s1 = manager.startCageWindow("missing_app", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        assertEquals(DesktopManager.DesktopState.ERROR, s1.state)
        assertTrue(s1.errorMessage.orEmpty().contains("missing_app"))
        assertEquals("apk: unable to select package missing_app\n", manager.cageCompositorLog(s1.sessionId))
        assertNotNull(manager.latestCageLog())
    }

    @Test
    fun appExitInsideGuestFiresExitCallback() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val port1 = startMockRfbServer()
        val guest = simulatedGuest { line, emit ->
            if (line.contains("cage-start 1 ")) emit("HCAGE:1:started\r\numl:~# ")
        }
        val exited = CopyOnWriteArrayList<String>()
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { listOf(port1) },
        )

        val s1 = manager.startCageWindow(
            command = "foot",
            outW = 720,
            outH = 1280,
            scale = 1f,
            multiWindow = false,
            swayRules = emptyList(),
            timeoutMs = 5_000,
            onSessionExit = { exited.add(it) },
        )
        assertEquals(DesktopManager.DesktopState.RUNNING, s1.state)

        // Simulate sway exiting when the user closes the app inside the guest.
        File(shareDir, "haven-kiosk-1.exit").writeText("0\n")

        withTimeout(3_000) {
            while (s1.sessionId !in exited) {
                delay(50)
            }
        }
        assertTrue(s1.sessionId in exited)
    }

    @Test
    fun startDesktopAutoInstallsInSameGuestBootWhenMarkerMissing() = runBlocking {
        val guest = simulatedGuest { line, emit ->
            when {
                line.contains("install x11") -> {
                    File(shareDir, "haven-desktop-x11.ok").writeText("ok")
                    emit("HDESKTOP:done\r\numl:~# ")
                }
                line.contains("start x11 ") -> {
                    val requestedPort = line.substringAfter("start x11 ").trim().toInt()
                    val server = ServerSocket(requestedPort, 8, InetAddress.getLoopbackAddress())
                    rfbServers.add(server)
                    thread(isDaemon = true) {
                        while (!server.isClosed) {
                            try {
                                val client = server.accept()
                                client.getOutputStream().write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                                client.getOutputStream().flush()
                                client.close()
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
                    emit("HDESKTOP:started\r\numl:~# ")
                }
            }
        }
        val dm = mockk<DesktopManager>(relaxed = true)
        every { dm.getPortPreference("uml", "x11") } returns 0
        every { dm.suggestNextVncPort("uml") } returns 5951
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = dm,
            cagePortAllocator = { emptyList() },
        )

        assertFalse(manager.state.value[UmlDesktopManager.Kind.X11]?.installed == true)
        manager.startDesktop(UmlDesktopManager.Kind.X11)

        withTimeout(5_000) {
            while (manager.state.value[UmlDesktopManager.Kind.X11]?.status !=
                UmlDesktopManager.DesktopState.Status.RUNNING
            ) {
                delay(25)
            }
        }
        val st = manager.state.value[UmlDesktopManager.Kind.X11]!!
        assertTrue(st.installed)
        assertEquals(UmlDesktopManager.DesktopState.Status.RUNNING, st.status)
        assertNotNull(st.port)
        verify(exactly = 1) { guest.registerSession(any(), any(), any(), any()) }
    }

    @Test
    fun ensureCageRuntimeInstallsWaylandPackagesOnceAndReusesMarker() = runBlocking {
        val sent = CopyOnWriteArrayList<String>()
        val guest = simulatedGuest(sent) { line, emit ->
            if (line.contains("install wayland")) {
                File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
                emit("HDESKTOP:done\r\numl:~# ")
            }
        }
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { emptyList() },
        )

        assertFalse(manager.isCageRuntimeReady())
        assertTrue(manager.ensureCageRuntime())
        assertTrue(manager.isCageRuntimeReady())
        assertEquals(1, sent.count { it.contains("install wayland") })

        // Second call is an immediate no-op when marker exists.
        assertTrue(manager.ensureCageRuntime())
        assertEquals(1, sent.count { it.contains("install wayland") })
    }

    @Test
    fun slotExhaustionReturnsClearErrorWhenAllCageSlotsOccupied() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val ports = (1..UmlDesktopManager.MAX_CAGE_SLOTS).map { startMockRfbServer() }
        val guest = simulatedGuest { line, emit ->
            for (slot in 1..UmlDesktopManager.MAX_CAGE_SLOTS) {
                if (line.contains("cage-start $slot ")) {
                    emit("HCAGE:$slot:started\r\numl:~# ")
                }
            }
        }
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { ports },
        )

        val running = (1..UmlDesktopManager.MAX_CAGE_SLOTS).map { i ->
            manager.startCageWindow("foot-$i", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        }
        running.forEach { assertEquals(DesktopManager.DesktopState.RUNNING, it.state) }

        val overflow = manager.startCageWindow("foot-extra", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        assertEquals(DesktopManager.DesktopState.ERROR, overflow.state)
        assertTrue(overflow.errorMessage.orEmpty().contains("slots are in use"))
    }

    @Test
    fun guestDisconnectDuringActiveCageWindowFiresExitCallbackAndRecoversOnNextLaunch() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val port1 = startMockRfbServer()
        val guest = simulatedGuest { line, emit ->
            if (line.contains("cage-start 1 ")) emit("HCAGE:1:started\r\numl:~# ")
        }
        val exited = CopyOnWriteArrayList<String>()
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { listOf(port1) },
        )

        val s1 = manager.startCageWindow(
            command = "foot",
            outW = 720,
            outH = 1280,
            scale = 1f,
            multiWindow = false,
            swayRules = emptyList(),
            timeoutMs = 5_000,
            onSessionExit = { exited.add(it) },
        )
        assertEquals(DesktopManager.DesktopState.RUNNING, s1.state)

        // Simulate the underlying UML kernel session crashing/disconnecting.
        guest.closeGuest("uml-sess-1")

        withTimeout(3_000) {
            while (s1.sessionId !in exited) {
                delay(50)
            }
        }
        assertTrue(s1.sessionId in exited)

        // Next cage launch boots a fresh guest session and succeeds on slot 1 again.
        val s2 = manager.startCageWindow("foot", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        assertEquals(DesktopManager.DesktopState.RUNNING, s2.state)
        verify(exactly = 2) { guest.registerSession(any(), any(), any(), any()) }
    }

    @Test
    fun concurrentDesktopAndCageWindowShareSingleWarmGuest() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val cagePort = startMockRfbServer()
        val sent = CopyOnWriteArrayList<String>()
        val guest = simulatedGuest(sent) { line, emit ->
            when {
                line.contains("start wayland ") -> {
                    val requestedPort = line.substringAfter("start wayland ").trim().toInt()
                    val server = ServerSocket(requestedPort, 8, InetAddress.getLoopbackAddress())
                    rfbServers.add(server)
                    thread(isDaemon = true) {
                        while (!server.isClosed) {
                            try {
                                val client = server.accept()
                                client.getOutputStream().write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
                                client.getOutputStream().flush()
                                client.close()
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
                    emit("HDESKTOP:started\r\numl:~# ")
                }
                line.contains("cage-start 1 ") -> emit("HCAGE:1:started\r\numl:~# ")
                line.contains("stop wayland") -> emit("HDESKTOP:stopped\r\numl:~# ")
            }
        }
        val dm = mockk<DesktopManager>(relaxed = true)
        every { dm.getPortPreference("uml", "wayland") } returns 0
        every { dm.suggestNextVncPort("uml") } returns 5952
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = dm,
            cagePortAllocator = { listOf(cagePort) },
        )

        manager.startDesktop(UmlDesktopManager.Kind.WAYLAND)
        withTimeout(5_000) {
            while (manager.state.value[UmlDesktopManager.Kind.WAYLAND]?.status !=
                UmlDesktopManager.DesktopState.Status.RUNNING
            ) {
                delay(25)
            }
        }

        val cage = manager.startCageWindow("foot", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)
        assertEquals(DesktopManager.DesktopState.RUNNING, cage.state)
        assertEquals(cagePort, cage.vncPort)
        // Both the Wayland desktop and the cage window share the single booted guest.
        verify(exactly = 1) { guest.registerSession(any(), any(), any(), any()) }

        // Stopping the desktop while a cage window is still active stops only the desktop
        // daemons (`stop wayland`) and does NOT power off the shared guest.
        manager.stopDesktop(UmlDesktopManager.Kind.WAYLAND)
        withTimeout(5_000) {
            while (manager.state.value[UmlDesktopManager.Kind.WAYLAND]?.status !=
                UmlDesktopManager.DesktopState.Status.STOPPED
            ) {
                delay(25)
            }
        }
        verify(exactly = 0) { guest.closeGuest(any()) }
        assertTrue(sent.any { it.contains("stop wayland") })
    }

    @Test
    fun scanInstalledAppsParsesGuestDesktopEntries() = runBlocking {
        File(shareDir, "haven-desktop-wayland.ok").writeText("ok")
        val port1 = startMockRfbServer()
        val guest = simulatedGuest { line, emit ->
            when {
                line.contains("cage-start 1 ") -> emit("HCAGE:1:started\r\numl:~# ")
                line.contains("cage-apps") -> {
                    val dir = File(shareDir, "applications").apply { mkdirs() }
                    File(dir, "foot.desktop").writeText(
                        """
                        [Desktop Entry]
                        Type=Application
                        Name=Foot
                        Exec=foot %u
                        Icon=foot
                        Categories=System;TerminalEmulator;
                        """.trimIndent(),
                    )
                    File(dir, "hidden.desktop").writeText(
                        """
                        [Desktop Entry]
                        Type=Application
                        Name=HiddenHelper
                        Exec=helper
                        NoDisplay=true
                        """.trimIndent(),
                    )
                    emit("HCAGE:apps:done\r\numl:~# ")
                }
            }
        }
        val manager = UmlDesktopManager(
            guestManager = guest,
            desktopManager = mockk(relaxed = true),
            cagePortAllocator = { listOf(port1) },
        )

        // Boot warm guest via a cage window so scanInstalledApps runs live `cage-apps`.
        manager.startCageWindow("foot", 720, 1280, 1f, false, emptyList(), timeoutMs = 5_000)

        val result = manager.scanInstalledApps()
        assertEquals(1, result.apps.size)
        assertEquals("Foot", result.apps[0].name)
        assertEquals("foot", result.apps[0].exec)
    }
}

