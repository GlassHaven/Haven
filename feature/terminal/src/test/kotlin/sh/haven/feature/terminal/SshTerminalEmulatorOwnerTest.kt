package sh.haven.feature.terminal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.ssh.TerminalSession
import sh.haven.feature.terminal.agent.TerminalSessionRegistry

/**
 * JVM-level proof of the connect-time emulator owner's lifecycle (#290 issue
 * #2): the emulator behavior itself needs JNI libvterm, so it's mocked here; the
 * device checklist covers the live tmux-probe timing.
 */
class SshTerminalEmulatorOwnerTest {

    private fun makeOwner(
        sessions: Map<String, SshSessionManager.SessionState>,
    ): Pair<SshTerminalEmulatorOwner, TerminalSessionRegistry> {
        val registry = TerminalSessionRegistry()
        val ssh = mockk<SshSessionManager>(relaxed = true) {
            every { this@mockk.sessions } returns MutableStateFlow(sessions)
        }
        val repo = mockk<ConnectionRepository>(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }
        val prefs = mockk<UserPreferencesRepository>(relaxed = true) {
            every { verboseLoggingEnabled } returns flowOf(false)
            every { terminalScrollbackRows } returns flowOf(UserPreferencesRepository.DEFAULT_SCROLLBACK_ROWS)
            every { terminalColorScheme } returns flowOf(UserPreferencesRepository.TerminalColorScheme.HAVEN)
            every { terminalInlineImages } returns flowOf(UserPreferencesRepository.TerminalInlineImages.ASK)
        }
        val owner = SshTerminalEmulatorOwner(ssh, repo, mockk(relaxed = true), prefs, registry, InlineImageConsentRegistry())
        // No JNI in unit tests — stand in a mock emulator.
        owner.emulatorFactory = SshTerminalEmulatorOwner.EmulatorFactory { _, _, _, _, _, _, _ -> mockk(relaxed = true) }
        return owner to registry
    }

    private fun state(id: String) = SshSessionManager.SessionState(
        sessionId = id,
        profileId = "p1",
        label = "lbl",
        status = SshSessionManager.SessionState.Status.CONNECTED,
        client = mockk<SshClient>(relaxed = true),
    )

    @Test
    fun `attach registers the emulator and onReady routes input to the session`() {
        val (owner, registry) = makeOwner(mapOf("s1" to state("s1")))

        val att = owner.attach("s1")
        assertNotNull("attach returns an attachment", att)
        assertNotNull("emulator registered for agent reads at connect", registry.get("s1"))

        val session = mockk<TerminalSession>(relaxed = true)
        att!!.onReady(session)

        owner.bundleFor("s1")!!.inputSink("hi".toByteArray())
        verify { session.sendToSsh(any()) }
    }

    @Test
    fun `setInputSink swaps routing and resetSinks restores it to the session`() {
        val (owner, _) = makeOwner(mapOf("s1" to state("s1")))
        val att = owner.attach("s1")!!
        val session = mockk<TerminalSession>(relaxed = true)
        att.onReady(session)

        val captured = mutableListOf<ByteArray>()
        owner.setInputSink("s1") { captured.add(it) }
        owner.bundleFor("s1")!!.inputSink("a".toByteArray())
        assertEquals("swapped sink received input", 1, captured.size)

        owner.resetSinks("s1")
        owner.bundleFor("s1")!!.inputSink("b".toByteArray())
        // After reset, input goes to the session, not the (now-detached) swapped sink.
        assertEquals("swapped sink no longer receives after reset", 1, captured.size)
        verify { session.sendToSsh(any()) }
    }

    @Test
    fun `dispose unregisters the emulator and drops the bundle`() {
        val (owner, registry) = makeOwner(mapOf("s1" to state("s1")))
        owner.attach("s1")
        assertNotNull(registry.get("s1"))

        owner.dispose("s1")
        assertNull("registry entry cleared on dispose", registry.get("s1"))
        assertNull("bundle dropped on dispose", owner.bundleFor("s1"))
    }
}
