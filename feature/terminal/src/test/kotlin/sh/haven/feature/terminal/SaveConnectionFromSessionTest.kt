package sh.haven.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.SessionManager

class SaveConnectionFromSessionTest {

    private fun ssh(
        id: String = "src",
        label: String = "sever",
        host: String = "seer.local",
        username: String = "tin",
        port: Int = 22,
        useMosh: Boolean = true,
        keyId: String? = "key-1",
    ) = ConnectionProfile(
        id = id,
        label = label,
        host = host,
        port = port,
        username = username,
        useMosh = useMosh,
        keyId = keyId,
        connectionType = "SSH",
    )

    @Test
    fun pinRemoteCommand_tmux() {
        assertEquals("tmux new -A -s Grok-haven", SaveConnectionFromSession.pinRemoteCommand(SessionManager.TMUX, "Grok-haven"))
    }

    @Test
    fun pinRemoteCommand_psmux() {
        assertEquals(
            "psmux attach -t work || psmux new-session -s work",
            SaveConnectionFromSession.pinRemoteCommand(SessionManager.PSMUX, "work"),
        )
    }

    @Test
    fun pinRemoteCommand_sanitizesDots() {
        assertEquals("tmux new -A -s user-10-0-0-5", SaveConnectionFromSession.pinRemoteCommand(SessionManager.TMUX, "user@10.0.0.5"))
    }

    @Test
    fun pinRemoteCommand_noneIsNull() {
        assertNull(SaveConnectionFromSession.pinRemoteCommand(SessionManager.NONE, "x"))
    }

    @Test
    fun draft_rejectsNonSshAndPlainShell() {
        val local = ssh().copy(connectionType = "LOCAL")
        assertNull(SaveConnectionFromSession.draft(local, "maid", SessionManager.TMUX, emptyList()))
        assertNull(SaveConnectionFromSession.draft(ssh(), "maid", SessionManager.NONE, emptyList()))
        // Empty only — whitespace sanitizes to "---", which is a valid session token.
        assertNull(SaveConnectionFromSession.draft(ssh(), "", SessionManager.TMUX, emptyList()))
    }

    @Test
    fun draft_newProfileGetsFreshId() {
        val d = SaveConnectionFromSession.draft(
            source = ssh(),
            sessionName = "Grok-haven",
            manager = SessionManager.TMUX,
            existing = emptyList(),
            newId = { "new-uuid" },
        )
        assertNotNull(d)
        assertEquals("new-uuid", d!!.profileId)
        assertEquals("Grok-haven", d.defaultName)
        assertFalse(d.isUpdate)
    }

    @Test
    fun draft_upsertWhenLabelHostUserMatch() {
        val existing = listOf(ssh(id = "existing", label = "Maid"))
        val d = SaveConnectionFromSession.draft(
            source = ssh(label = "other"),
            sessionName = "maid",
            manager = SessionManager.TMUX,
            existing = existing,
            defaultLabel = "Maid",
            newId = { "should-not-use" },
        )
        assertNotNull(d)
        assertEquals("existing", d!!.profileId)
        assertTrue(d.isUpdate)
    }

    @Test
    fun build_createsPinnedClone() {
        val source = ssh(useMosh = true, keyId = "k1")
        val result = SaveConnectionFromSession.build(
            source = source,
            displayName = "Grok",
            sessionName = "Grok-haven",
            manager = SessionManager.TMUX,
            existing = emptyList(),
            preferredId = "p1",
        )
        assertNotNull(result)
        assertTrue(result!!.created)
        val p = result.profile
        assertEquals("p1", p.id)
        assertEquals("Grok", p.label)
        assertEquals("tmux new -A -s Grok-haven", p.remoteCommand)
        assertEquals("Grok-haven", p.lastSessionName)
        assertEquals("TMUX", p.sessionManager)
        assertTrue(p.requestPty)
        assertTrue(p.useMosh)
        assertEquals("k1", p.keyId)
        assertNull(p.postLoginCommand)
    }

    @Test
    fun build_updatesExistingOnNameCollision() {
        val source = ssh(id = "live", label = "live-tab", useMosh = false)
        val existing = listOf(
            ssh(id = "old", label = "Grok", useMosh = true, keyId = "old-key").copy(
                remoteCommand = "tmux new -A -s old-name",
                lastSessionName = "old-name",
            ),
        )
        val result = SaveConnectionFromSession.build(
            source = source,
            displayName = "Grok",
            sessionName = "Grok-haven",
            manager = SessionManager.TMUX,
            existing = existing,
            preferredId = "unused",
        )
        assertNotNull(result)
        assertFalse(result!!.created)
        assertEquals("old", result.profile.id)
        assertEquals("tmux new -A -s Grok-haven", result.profile.remoteCommand)
        assertEquals("Grok-haven", result.profile.lastSessionName)
        // Live source wins for transport/auth refresh
        assertFalse(result.profile.useMosh)
    }

    @Test
    fun build_preservesCustomFleetWrapperRemoteCommand() {
        val wrapper =
            "bash /home/tin/Central_Command/03_toolkit/bin/haven-role-attach dogfood"
        val source = ssh(id = "live", label = "seer · Dogfood", useMosh = false).copy(
            remoteCommand = wrapper,
        )
        val existing = listOf(
            ssh(id = "dogfood", label = "seer · Dogfood", useMosh = true, keyId = "k").copy(
                remoteCommand = wrapper,
                lastSessionName = "haven-dogfood",
            ),
        )
        val result = SaveConnectionFromSession.build(
            source = source,
            displayName = "seer · Dogfood",
            sessionName = "haven-dogfood",
            manager = SessionManager.TMUX,
            existing = existing,
            preferredId = "unused",
        )
        assertNotNull(result)
        assertFalse(result!!.created)
        // Must NOT clobber fleet L4 wrapper with plain tmux pin
        assertEquals(wrapper, result.profile.remoteCommand)
        assertEquals("haven-dogfood", result.profile.lastSessionName)
    }

    @Test
    fun isStandardMultiplexerPin_distinguishesWrapper() {
        assertTrue(SaveConnectionFromSession.isStandardMultiplexerPin("tmux new -A -s maid"))
        assertFalse(
            SaveConnectionFromSession.isStandardMultiplexerPin(
                "bash /home/tin/Central_Command/03_toolkit/bin/haven-role-attach dogfood",
            ),
        )
    }

    @Test
    fun build_zellijPin() {
        val result = SaveConnectionFromSession.build(
            source = ssh(),
            displayName = "z",
            sessionName = "work",
            manager = SessionManager.ZELLIJ,
            existing = emptyList(),
            preferredId = "z1",
        )
        assertEquals("zellij attach work --create", result!!.profile.remoteCommand)
    }

    @Test
    fun sessionNameFromRemoteCommand_parsesKnownPins() {
        assertEquals(
            "Grok-haven",
            SaveConnectionFromSession.sessionNameFromRemoteCommand("tmux new -A -s Grok-haven"),
        )
        assertEquals(
            "work",
            SaveConnectionFromSession.sessionNameFromRemoteCommand("zellij attach work --create"),
        )
        assertEquals(
            "scr",
            SaveConnectionFromSession.sessionNameFromRemoteCommand("screen -dRR scr"),
        )
        assertNull(SaveConnectionFromSession.sessionNameFromRemoteCommand("htop"))
    }
}
