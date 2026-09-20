package sh.haven.feature.terminal.agent

import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.terminal.ComposeController
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.feature.terminal.OscHandler

/**
 * Registry semantics behind the #378 fix: open_local_shell claims the slot
 * atomically via [TerminalSessionRegistry.registerHeadless], and when a UI
 * tab wins (or the claim raced a tab build), syncSessions repoints the entry
 * at the tab's live handles via [TerminalSessionRegistry.adoptTabHandles].
 */
class TerminalSessionRegistryTest {

    private val registry = TerminalSessionRegistry()
    private val feedA: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }
    private val feedB: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }

    private fun registerHeadless(
        sessionId: String,
        emulator: TerminalEmulator,
        feed: (ByteArray, Int, Int) -> Unit = feedA,
    ) = registry.registerHeadless(
        sessionId,
        emulator,
        feed,
        mouseMode = MutableStateFlow(false),
        activeMouseMode = MutableStateFlow<Int?>(null),
        bracketPasteMode = MutableStateFlow(false),
        altScreen = MutableStateFlow(false),
        cursorKeyAppMode = MutableStateFlow(false),
    )

    @Test
    fun `registerHeadless claims an empty slot with feed and mode flows in one write`() {
        val emulator = mockk<TerminalEmulator>()

        assertTrue(registerHeadless("s1", emulator))

        val entry = registry.get("s1")!!
        assertSame(emulator, entry.emulator)
        assertSame(feedA, entry.feedOutput)
        assertFalse(entry.mouseMode!!.value)
        // No OSC handler on a headless claim — a later same-emulator tab
        // adoption must still attach the full tab handles (#336).
        assertNull(entry.oscHandler)
    }

    @Test
    fun `registerHeadless loses to an existing tab entry and leaves it untouched`() {
        val tabEmulator = mockk<TerminalEmulator>()
        registry.register("s1", tabEmulator)

        assertFalse(registerHeadless("s1", mockk<TerminalEmulator>()))

        assertSame(tabEmulator, registry.get("s1")!!.emulator)
        assertNull(registry.get("s1")!!.feedOutput)
    }

    @Test
    fun `adoptTabHandles repoints emulator feed and osc at the tab`() {
        val headless = mockk<TerminalEmulator>()
        val tabEmulator = mockk<TerminalEmulator>()
        val tabOsc = OscHandler()
        assertTrue(registerHeadless("s1", headless, feedA))

        registry.adoptTabHandles(
            "s1",
            tabEmulator,
            mouseMode = MutableStateFlow(true),
            activeMouseMode = MutableStateFlow<Int?>(1002),
            bracketPasteMode = MutableStateFlow(true),
            altScreen = MutableStateFlow(true),
            cursorKeyAppMode = MutableStateFlow(false),
            oscHandler = tabOsc,
            feedOutput = feedB,
        )

        val entry = registry.get("s1")!!
        assertSame(tabEmulator, entry.emulator)
        assertSame(feedB, entry.feedOutput)
        assertSame(tabOsc, entry.oscHandler)
        assertTrue(entry.mouseMode!!.value)
        assertEquals(1002, entry.activeMouseMode!!.value)
        assertTrue(entry.altScreen!!.value)
        assertFalse(entry.cursorKeyAppMode!!.value)
    }

    @Test
    fun `adoptTabHandles preserves controllers set by the tab's composition`() {
        val selection = mockk<SelectionController>()
        val scroll = mockk<ScrollController>()
        val compose = mockk<ComposeController>()
        assertTrue(registerHeadless("s1", mockk<TerminalEmulator>()))
        registry.setSelectionController("s1", selection)
        registry.setScrollController("s1", scroll)
        registry.setComposeController("s1", compose)

        registry.adoptTabHandles(
            "s1",
            mockk<TerminalEmulator>(),
            mouseMode = MutableStateFlow(false),
            activeMouseMode = MutableStateFlow<Int?>(null),
            bracketPasteMode = MutableStateFlow(false),
            altScreen = MutableStateFlow(false),
            cursorKeyAppMode = MutableStateFlow(false),
            oscHandler = OscHandler(),
            feedOutput = feedB,
        )

        val entry = registry.get("s1")!!
        assertSame(selection, entry.selectionController)
        assertSame(scroll, entry.scrollController)
        assertSame(compose, entry.composeController)
    }

    @Test
    fun `adoptTabHandles is a no-op for an unregistered session`() {
        registry.adoptTabHandles(
            "ghost",
            mockk<TerminalEmulator>(),
            mouseMode = MutableStateFlow(false),
            activeMouseMode = MutableStateFlow<Int?>(null),
            bracketPasteMode = MutableStateFlow(false),
            altScreen = MutableStateFlow(false),
            cursorKeyAppMode = MutableStateFlow(false),
            oscHandler = OscHandler(),
            feedOutput = feedA,
        )
        assertNull(registry.get("ghost"))
    }

    // ------------------------------------------------------------------
    // Retained final screen for an exited session
    // ------------------------------------------------------------------

    private fun emulatorShowing(vararg lines: String): TerminalEmulator = mockk {
        io.mockk.every { buildAgentSnapshot(any(), any()) } returns org.connectbot.terminal.AgentSnapshot(
            rows = lines.size,
            cols = 80,
            cursorRow = 0,
            cursorCol = 0,
            cursorVisible = true,
            terminalTitle = "",
            scrollbackSize = 0,
            lines = lines.map { org.connectbot.terminal.AgentLine(text = it, softWrapped = false, semanticSegments = emptyList()) },
        )
    }

    /**
     * A session that exits takes its tab with it, so every live-tab read verb
     * fails at exactly the moment the output would explain why it exited.
     */
    @Test
    fun `unregister keeps the final screen`() {
        registerHeadless("s1", emulatorShowing("bash: /usr/bin/thing: No such file", "", ""))
        registry.unregister("s1", profileId = "p1", label = "box")

        val exited = registry.lastExited()
        assertEquals("s1", exited?.sessionId)
        assertEquals("p1", exited?.profileId)
        assertEquals("box", exited?.label)
        // Trailing blanks are noise on a 47-row screen showing one line.
        assertEquals(listOf("bash: /usr/bin/thing: No such file"), exited?.screen)
    }

    /** Scoping by profile is the point: "why did THAT profile die?" */
    @Test
    fun `lastExited filters by profile`() {
        registerHeadless("s1", emulatorShowing("from-p1"))
        registry.unregister("s1", profileId = "p1")
        registerHeadless("s2", emulatorShowing("from-p2"))
        registry.unregister("s2", profileId = "p2")

        assertEquals(listOf("from-p2"), registry.lastExited()?.screen)
        assertEquals(listOf("from-p1"), registry.lastExited("p1")?.screen)
        assertNull(registry.lastExited("nobody"))
    }

    /** Retention is bounded — a long session-churning run must not grow forever. */
    @Test
    fun `retention is capped and newest-first`() {
        repeat(12) { i ->
            registerHeadless("s$i", emulatorShowing("screen-$i"))
            registry.unregister("s$i", profileId = "p$i")
        }
        assertEquals(listOf("screen-11"), registry.lastExited()?.screen)
        assertNull("oldest entries must have been dropped", registry.lastExited("p0"))
        assertEquals(listOf("screen-4"), registry.lastExited("p4")?.screen)
    }

    /** Teardown must not depend on the snapshot succeeding. */
    @Test
    fun `a failing snapshot still unregisters`() {
        val broken: TerminalEmulator = mockk {
            io.mockk.every { buildAgentSnapshot(any(), any()) } throws IllegalStateException("boom")
        }
        registerHeadless("s1", broken)
        registry.unregister("s1", profileId = "p1")
        assertNull(registry.get("s1"))
        assertNull(registry.lastExited("p1"))
    }
}
