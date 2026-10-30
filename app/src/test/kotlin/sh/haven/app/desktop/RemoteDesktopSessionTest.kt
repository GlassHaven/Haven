package sh.haven.app.desktop

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test
import sh.haven.core.rdp.RdpSession
import sh.haven.core.vnc.VncClient
import sh.haven.rdp.MouseButton

/**
 * Unit tests for the [RemoteDesktopSession] adapters introduced for
 * #128. These pin the verb-routing contract between the abstraction
 * and the underlying drivers — anything regressing the mapping
 * (button numbers, scroll-as-click-4/5, pause/resume direction)
 * surfaces here long before it reaches a connected VNC/RDP server.
 */
class RemoteDesktopSessionTest {

    // --- VncDesktopSession -------------------------------------------------

    @Test
    fun `vnc adapter forwards mouse move`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).sendMouseMove(120, 240)
        verify(exactly = 1) { client.moveMouse(120, 240) }
    }

    @Test
    fun `vnc adapter forwards button press and release`() {
        val client = mockk<VncClient>(relaxed = true)
        val s = VncDesktopSession(client)
        s.sendMouseButton(button = 1, pressed = true)
        s.sendMouseButton(button = 1, pressed = false)
        verifyOrder {
            client.updateMouseButton(1, true)
            client.updateMouseButton(1, false)
        }
    }

    @Test
    fun `vnc adapter click is move plus click`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).sendMouseClick(50, 75, button = 3)
        verifyOrder {
            client.moveMouse(50, 75)
            client.click(3)
        }
    }

    @Test
    fun `vnc adapter scroll up synthesises click on button 4`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).sendMouseWheel(deltaY = 1)
        verify(exactly = 1) { client.click(4) }
    }

    @Test
    fun `vnc adapter scroll down synthesises click on button 5`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).sendMouseWheel(deltaY = -1)
        verify(exactly = 1) { client.click(5) }
    }

    @Test
    fun `vnc adapter zero scroll is a no-op`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).sendMouseWheel(deltaY = 0)
        verify(exactly = 0) { client.click(any()) }
    }

    @Test
    fun `vnc adapter forwards clipboard text`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).sendClipboardText("hello")
        verify(exactly = 1) { client.copyText("hello") }
    }

    @Test
    fun `vnc adapter pause toggles paused property`() {
        val client = mockk<VncClient>(relaxed = true)
        every { client.paused = any() } returns Unit
        val s = VncDesktopSession(client)
        s.pause()
        s.resume()
        verifyOrder {
            client.paused = true
            client.paused = false
        }
    }

    @Test
    fun `vnc adapter close stops the client`() {
        val client = mockk<VncClient>(relaxed = true)
        VncDesktopSession(client).close()
        verify(exactly = 1) { client.stop() }
    }

    // --- RdpDesktopSession -------------------------------------------------

    @Test
    fun `rdp adapter forwards mouse move`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseMove(10, 20)
        verify(exactly = 1) { session.sendMouseMove(10, 20) }
    }

    @Test
    fun `rdp adapter maps x11 button 1 to LEFT`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseButton(button = 1, pressed = true)
        verify(exactly = 1) { session.sendMouseButton(MouseButton.LEFT, true) }
    }

    @Test
    fun `rdp adapter sendMouseClick translates the button number`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseClick(5, 6, button = 1)
        verify(exactly = 1) { session.sendMouseClick(5, 6, MouseButton.LEFT) }
    }

    @Test
    fun `rdp adapter scroll up sends positive vertical wheel`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseWheel(deltaY = 1)
        verify(exactly = 1) { session.sendMouseWheel(true, 120) }
    }

    @Test
    fun `rdp adapter scroll down sends negative vertical wheel`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseWheel(deltaY = -1)
        verify(exactly = 1) { session.sendMouseWheel(true, -120) }
    }

    @Test
    fun `rdp adapter zero scroll is a no-op`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendMouseWheel(deltaY = 0)
        verify(exactly = 0) { session.sendMouseWheel(any(), any()) }
    }

    @Test
    fun `rdp adapter forwards clipboard text`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).sendClipboardText("hi")
        verify(exactly = 1) { session.sendClipboardText("hi") }
    }

    @Test
    fun `rdp adapter pause and resume are no-ops`() {
        // RDP has no client-side throttle — the abstraction's pause /
        // resume must not blow up, just no-op.
        val session = mockk<RdpSession>(relaxed = true)
        val s = RdpDesktopSession(session)
        s.pause()
        s.resume()
        // Verify nothing on the underlying session was called.
        verify(exactly = 0) { session.sendMouseMove(any(), any()) }
        verify(exactly = 0) { session.close() }
    }

    @Test
    fun `rdp adapter close calls session close`() {
        val session = mockk<RdpSession>(relaxed = true)
        RdpDesktopSession(session).close()
        verify(exactly = 1) { session.close() }
    }

    // --- GlkvmDesktopSession + DesktopToolProvider -------------------------

    @Test
    fun `glkvm refreshFrameIfNeeded pulls fresh snapshot after input or when paused`() {
        val client = mockk<sh.haven.core.glkvm.GlkvmClient>(relaxed = true)
        var snapshotCalls = 0
        val s = GlkvmDesktopSession(
            client = client,
            onFrame = {},
            onConnected = {},
            onError = {},
            onDisconnected = {},
            pollSnap = { snapshotCalls++ },
        )
        // Before any frame has been fetched, refreshFrameIfNeeded fetches once.
        s.refreshFrameIfNeeded(force = false, minSettleMs = 0L)
        org.junit.Assert.assertEquals(1, snapshotCalls)

        // With no subsequent input and not paused, a non-forced refresh is a no-op.
        s.refreshFrameIfNeeded(force = false, minSettleMs = 0L)
        org.junit.Assert.assertEquals(1, snapshotCalls)

        // After an input action completes, refreshFrameIfNeeded pulls a fresh frame.
        s.sendKey("PageUp", true)
        s.refreshFrameIfNeeded(force = false, minSettleMs = 0L)
        org.junit.Assert.assertEquals(2, snapshotCalls)

        // When paused, refreshFrameIfNeeded pulls a fresh frame even without new input.
        s.pause()
        s.refreshFrameIfNeeded(force = false, minSettleMs = 0L)
        org.junit.Assert.assertEquals(3, snapshotCalls)
    }

    @Test
    fun `scroll_desktop_tab maps positive deltaY to scroll down and moves mouse when x y given`() = kotlinx.coroutines.runBlocking {
        val registry = sh.haven.core.data.desktop.DesktopSessionRegistry()
        val moves = mutableListOf<Pair<Int, Int>>()
        val wheels = mutableListOf<Int>()
        registry.registerInputHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopInputHandle(
                protocol = "GLKVM",
                mouseMove = { x, y -> moves.add(x to y) },
                mouseClick = { _, _, _ -> },
                mouseWheel = { d -> wheels.add(d) },
                clipboard = {},
            ),
        )
        val provider = sh.haven.app.agent.DesktopToolProvider(
            ctx = mockk(relaxed = true),
            desktopSessionRegistry = registry,
            localSessionManager = mockk(relaxed = true),
        )
        val scroll = provider.tools()["scroll_desktop_tab"]!!

        // Schema: deltaY > 0 scrolls down -> RemoteDesktopSession convention is negative for down.
        scroll.handle(org.json.JSONObject().put("deltaY", 5).put("x", 600).put("y", 300))
        org.junit.Assert.assertEquals(listOf(600 to 300), moves)
        org.junit.Assert.assertEquals(listOf(-5), wheels)

        // Schema: deltaY < 0 scrolls up -> RemoteDesktopSession convention is positive for up.
        scroll.handle(org.json.JSONObject().put("deltaY", -3))
        org.junit.Assert.assertEquals(listOf(-5, 3), wheels)
    }

    @Test
    fun `type_desktop_text repeat taps key multiple times`() = kotlinx.coroutines.runBlocking {
        val registry = sh.haven.core.data.desktop.DesktopSessionRegistry()
        val keys = mutableListOf<Pair<String, Boolean>>()
        registry.registerInputHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopInputHandle(
                protocol = "GLKVM",
                mouseMove = { _, _ -> },
                mouseClick = { _, _, _ -> },
                mouseWheel = {},
                clipboard = {},
                sendKey = { code, pressed -> keys.add(code to pressed) },
            ),
        )
        val provider = sh.haven.app.agent.DesktopToolProvider(
            ctx = mockk(relaxed = true),
            desktopSessionRegistry = registry,
            localSessionManager = mockk(relaxed = true),
        )
        val typeTool = provider.tools()["type_desktop_text"]!!
        val res = typeTool.handle(
            org.json.JSONObject().put("key", "PageUp").put("repeat", 3),
        ).structured

        org.junit.Assert.assertEquals(3, res.getInt("repeat"))
        org.junit.Assert.assertEquals(
            listOf(
                "PageUp" to true, "PageUp" to false,
                "PageUp" to true, "PageUp" to false,
                "PageUp" to true, "PageUp" to false,
            ),
            keys,
        )
    }

    @Test
    fun `inspect_desktop_tab calls refreshFrame before indexing`() = kotlinx.coroutines.runBlocking {
        val registry = sh.haven.core.data.desktop.DesktopSessionRegistry()
        val refreshArgs = mutableListOf<Boolean>()
        val bmp = mockk<android.graphics.Bitmap>(relaxed = true)
        val recognizer = mockk<sh.haven.core.scan.TextRecognizer>(relaxed = true)
        val fakeIndex = mockk<sh.haven.core.scan.OcrScreenIndex>(relaxed = true)
        every { fakeIndex.frameWidth } returns 2560
        every { fakeIndex.frameHeight } returns 1440
        every { fakeIndex.frameHash } returns "abc"
        every { fakeIndex.blocks } returns emptyList()
        io.mockk.coEvery { recognizer.recognizeIndex(any(), forceRefresh = true) } returns fakeIndex
        registry.registerFrameHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopFrameHandle(
                protocol = "GLKVM",
                frame = { null },
                cursor = { null },
                pointer = { 100 to 200 },
                refreshFrame = { force ->
                    refreshArgs.add(force)
                    bmp
                },
            ),
        )
        val provider = sh.haven.app.agent.DesktopToolProvider(
            ctx = mockk(relaxed = true),
            desktopSessionRegistry = registry,
            localSessionManager = mockk(relaxed = true),
            textRecognizer = recognizer,
        )
        val inspect = provider.tools()["inspect_desktop_tab"]!!
        val res = inspect.handle(org.json.JSONObject().put("forceRefresh", true)).structured

        org.junit.Assert.assertEquals(listOf(true), refreshArgs)
        org.junit.Assert.assertEquals("abc", res.getString("frameHash"))
    }

    @Test
    fun `tap_desktop_tab clicks multiple points in sequence`() = kotlinx.coroutines.runBlocking {
        val registry = sh.haven.core.data.desktop.DesktopSessionRegistry()
        val clicks = mutableListOf<Triple<Int, Int, Int>>()
        registry.registerInputHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopInputHandle(
                protocol = "GLKVM",
                mouseClick = { x, y, btn -> clicks.add(Triple(x, y, btn)) },
                mouseMove = { _, _ -> },
                mouseButton = { _, _ -> },
                mouseWheel = { _ -> },
                typeText = null,
                sendKey = null,
                clipboard = { _ -> },
            ),
        )
        val provider = sh.haven.app.agent.DesktopToolProvider(
            ctx = mockk(relaxed = true),
            desktopSessionRegistry = registry,
            localSessionManager = mockk(relaxed = true),
        )
        val tapTool = provider.tools()["tap_desktop_tab"]!!
        val points = org.json.JSONArray()
            .put(org.json.JSONObject().put("x", 160).put("y", 630).put("delayMs", 0))
            .put(org.json.JSONObject().put("x", 450).put("y", 680).put("delayMs", 1))
        val res = tapTool.handle(
            org.json.JSONObject().put("points", points),
        ).structured

        org.junit.Assert.assertEquals(
            listOf(Triple(160, 630, 1), Triple(450, 680, 1)),
            clicks,
        )
        org.junit.Assert.assertEquals(2, res.getJSONArray("pointsClicked").length())
        org.junit.Assert.assertEquals(450, res.getInt("x"))
        org.junit.Assert.assertEquals(680, res.getInt("y"))
    }

    @Test
    fun `tap_desktop_text filters matches by minY and returns candidates`() = kotlinx.coroutines.runBlocking {
        val registry = sh.haven.core.data.desktop.DesktopSessionRegistry()
        val clicked = mutableListOf<Pair<Int, Int>>()
        val bmp = mockk<android.graphics.Bitmap>(relaxed = true)
        val recognizer = mockk<sh.haven.core.scan.TextRecognizer>(relaxed = true)
        val topTab = sh.haven.core.scan.OcrScreenIndex.TextMatchResult(
            node = sh.haven.core.scan.OcrNode(
                id = "l0",
                level = sh.haven.core.scan.OcrNode.Level.LINE,
                text = "Commits - Repos",
                score = 0.95f,
                bbox = sh.haven.core.scan.OcrBbox(400, 20, 200, 24),
                blockId = "b0",
                lineId = "l0",
            ),
            anchorDistance = null,
            neighbors = emptyList(),
        )
        val leftNav = sh.haven.core.scan.OcrScreenIndex.TextMatchResult(
            node = sh.haven.core.scan.OcrNode(
                id = "l1",
                level = sh.haven.core.scan.OcrNode.Level.LINE,
                text = "Repos",
                score = 0.98f,
                bbox = sh.haven.core.scan.OcrBbox(140, 670, 80, 24),
                blockId = "b1",
                lineId = "l1",
            ),
            anchorDistance = null,
            neighbors = emptyList(),
        )
        val fakeIndex = mockk<sh.haven.core.scan.OcrScreenIndex>(relaxed = true)
        every { fakeIndex.frameWidth } returns 2560
        every { fakeIndex.frameHeight } returns 1440
        every { fakeIndex.findText(any(), any(), any(), any(), any(), any()) } returns listOf(topTab, leftNav)
        io.mockk.coEvery { recognizer.recognizeIndex(any(), any()) } returns fakeIndex

        registry.registerFrameHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopFrameHandle(
                protocol = "GLKVM",
                frame = { bmp },
                cursor = { null },
                pointer = { 0 to 0 },
                activeBounds = { 128 to 2304 },
            ),
        )
        registry.registerInputHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopInputHandle(
                protocol = "GLKVM",
                mouseClick = { x, y, _ -> clicked.add(x to y) },
                mouseMove = { _, _ -> },
                mouseButton = { _, _ -> },
                mouseWheel = { _ -> },
                typeText = null,
                sendKey = null,
                clipboard = { _ -> },
            ),
        )
        val provider = sh.haven.app.agent.DesktopToolProvider(
            ctx = mockk(relaxed = true),
            desktopSessionRegistry = registry,
            localSessionManager = mockk(relaxed = true),
            textRecognizer = recognizer,
        )
        val tapText = provider.tools()["tap_desktop_text"]!!
        val res = tapText.handle(
            org.json.JSONObject().put("query", "Repos").put("minY", 160),
        ).structured

        org.junit.Assert.assertEquals(listOf(180 to 682), clicked)
        org.junit.Assert.assertEquals(1, res.getInt("matchCount"))
        org.junit.Assert.assertEquals("Repos", res.getJSONArray("candidates").getJSONObject(0).getString("text"))

        val inspect = provider.tools()["inspect_desktop_tab"]!!
        val inspectRes = inspect.handle(org.json.JSONObject().put("mode", "tree")).structured
        org.junit.Assert.assertEquals(128, inspectRes.getInt("activeLeftPx"))
        org.junit.Assert.assertEquals(2304, inspectRes.getInt("activeWidthPx"))
    }

    @Test
    fun `inspect_desktop_tab diff mode and type_desktop_text inspectAfter diff return added and removed lines`() = kotlinx.coroutines.runBlocking {
        val registry = sh.haven.core.data.desktop.DesktopSessionRegistry()
        val typed = mutableListOf<String>()
        val bmp = mockk<android.graphics.Bitmap>(relaxed = true)
        every { bmp.width } returns 1920
        every { bmp.height } returns 1080

        val index1 = sh.haven.core.scan.OcrScreenIndex.build(
            detections = listOf(
                sh.haven.core.scan.OcrLineDetection(sh.haven.core.scan.OcrBbox(20, 40, 200, 24), "root@kvm:~# ", 0.96f),
                sh.haven.core.scan.OcrLineDetection(sh.haven.core.scan.OcrBbox(20, 80, 180, 24), "Status: Idle", 0.95f),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
            frameHash = "h1",
        )
        val index2 = sh.haven.core.scan.OcrScreenIndex.build(
            detections = listOf(
                sh.haven.core.scan.OcrLineDetection(sh.haven.core.scan.OcrBbox(20, 40, 200, 24), "root@kvm:~# ", 0.96f),
                sh.haven.core.scan.OcrLineDetection(sh.haven.core.scan.OcrBbox(20, 80, 210, 24), "Status: Running", 0.97f),
                sh.haven.core.scan.OcrLineDetection(sh.haven.core.scan.OcrBbox(20, 120, 240, 24), " Worker spawned", 0.94f),
            ),
            frameWidth = 1920,
            frameHeight = 1080,
            frameHash = "h2",
        )

        val recognizer = mockk<sh.haven.core.scan.TextRecognizer>(relaxed = true)
        var callCount = 0
        io.mockk.coEvery { recognizer.recognizeIndex(any(), any(), any()) } answers {
            callCount++
            if (callCount == 1) index1 else index2
        }

        registry.registerFrameHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopFrameHandle(
                protocol = "GLKVM",
                frame = { bmp },
                cursor = { null },
                pointer = { 0 to 0 },
            ),
        )
        registry.registerInputHandle(
            "p1",
            sh.haven.core.data.desktop.DesktopInputHandle(
                protocol = "GLKVM",
                mouseClick = { _, _, _ -> },
                mouseMove = { _, _ -> },
                mouseButton = { _, _ -> },
                mouseWheel = { _ -> },
                typeText = { t -> typed.add(t) },
                sendKey = { _, _ -> },
                clipboard = { _ -> },
            ),
        )
        val provider = sh.haven.app.agent.DesktopToolProvider(
            ctx = mockk(relaxed = true),
            desktopSessionRegistry = registry,
            localSessionManager = mockk(relaxed = true),
            textRecognizer = recognizer,
        )
        val typeTool = provider.tools()["type_desktop_text"]!!
        val res = typeTool.handle(
            org.json.JSONObject()
                .put("text", "./start.sh")
                .put("pressEnter", true)
                .put("inspectAfter", "diff")
                .put("settleMs", 0),
        ).structured

        org.junit.Assert.assertEquals(listOf("./start.sh"), typed)
        val insp = res.getJSONObject("inspection")
        org.junit.Assert.assertEquals("diff", insp.getString("mode"))
        org.junit.Assert.assertTrue(insp.getBoolean("hasBaseline"))
        org.junit.Assert.assertTrue(insp.getBoolean("changed"))
        org.junit.Assert.assertEquals(1, insp.getInt("unchangedLineCount"))
        val added = insp.getJSONArray("addedLines")
        val removed = insp.getJSONArray("removedLines")
        org.junit.Assert.assertEquals(2, added.length())
        org.junit.Assert.assertEquals("Status: Running", added.getJSONObject(0).getString("text"))
        org.junit.Assert.assertEquals(1, removed.length())
        org.junit.Assert.assertEquals("Status: Idle", removed.getJSONObject(0).getString("text"))
    }

    @Test
    fun `glkvm sendMouseWheel reasserts last target mouse position after wobble window`() {
        val client = mockk<sh.haven.core.glkvm.GlkvmClient>(relaxed = true)
        val s = GlkvmDesktopSession(
            client = client,
            onFrame = {},
            onConnected = {},
            onError = {},
            onDisconnected = {},
        )
        s.sendMouseMove(640, 360)
        // Immediate wheel scroll does not need to re-send mouse move.
        s.sendMouseWheel(deltaY = -2)
        verifyOrder {
            client.sendMouseMove(640, 360)
            client.sendWheel(2)
        }
        verify(exactly = 1) { client.sendMouseMove(640, 360) }

        // After the wobble re-assert window, sendMouseWheel re-asserts (640, 360) before scrolling.
        Thread.sleep(GlkvmDesktopSession.GLKVM_WOBBLE_REASSERT_MS + 30L)
        s.sendMouseWheel(deltaY = -3)
        verify(exactly = 2) { client.sendMouseMove(640, 360) }
        verify(exactly = 1) { client.sendWheel(3) }
    }
}
