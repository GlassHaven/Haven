package sh.haven.feature.connections

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.ssh.ConnectionConfig
import sh.haven.core.ssh.ExecResult
import sh.haven.core.ssh.SessionManager

/**
 * Pins the shared Mosh/ET bootstrap pieces extracted from the four
 * previously-duplicated connect blocks in ConnectionsViewModel: config
 * construction (interactive vs silent mode differences) and best-effort
 * session listing.
 */
class MoshEtBootstrapTest {

    private val profile = ConnectionProfile(
        id = "p1", label = "box", host = "example.com", port = 2222,
        username = "ian", connectionType = "SSH", forwardAgent = true,
    )
    private val auth = ConnectionConfig.AuthMethod.Password("pw")

    @Test fun silentDefaultsKeepProfileUsernameAndDefaultReconnectPolicy() {
        val config = moshEtBootstrapConfig(profile, auth, agentIdentities = emptyList())
        assertEquals("example.com", config.host)
        assertEquals(2222, config.port)
        assertEquals("ian", config.username)
        assertEquals(true, config.forwardAgent)
        // Silent connects historically omitted reconnectPolicy → class default.
        assertEquals(ConnectionConfig.ReconnectPolicy(), config.reconnectPolicy)
    }

    @Test fun interactiveOverridesUsernameAndHonoursProfileReconnectPolicy() {
        val p = profile.copy(autoReconnect = false, reconnectMaxAttempts = 9)
        val config = moshEtBootstrapConfig(
            p, auth, agentIdentities = emptyList(),
            username = "root", reconnectPolicy = p.reconnectPolicy,
        )
        assertEquals("root", config.username)
        assertEquals(
            ConnectionConfig.ReconnectPolicy(autoReconnect = false, maxAttempts = 9, onNetworkChange = p.reconnectOnNetworkChange),
            config.reconnectPolicy,
        )
    }

    // --- best-effort session listing ---

    private fun exec(status: Int, stdout: String): suspend (String) -> ExecResult =
        { ExecResult(exitStatus = status, stdout = stdout, stderr = "") }

    @Test fun parsesNamesOnlyForSuccessfulCommands() = runBlocking {
        val names = listExistingMultiplexerSessions(SessionManager.TMUX, exec(0, "main\nwork\n"))
        assertEquals(listOf("main", "work"), names)
    }

    @Test fun nonZeroExitYieldsEmpty() = runBlocking {
        val names = listExistingMultiplexerSessions(SessionManager.TMUX, exec(127, "tmux: not found"))
        assertTrue(names.isEmpty())
    }

    @Test fun execFailureYieldsEmpty() = runBlocking {
        val names = listExistingMultiplexerSessions(SessionManager.TMUX) {
            throw java.io.IOException("channel closed")
        }
        assertTrue(names.isEmpty())
    }

    @Test fun managerWithoutListCommandSkipsExecEntirely() = runBlocking {
        var called = false
        val names = listExistingMultiplexerSessions(SessionManager.NONE) {
            called = true
            ExecResult(0, "", "")
        }
        assertTrue(names.isEmpty())
        assertTrue(!called)
    }

    // --- saved mosh re-attach eligibility (#371) ---

    private val savedProfile = profile.copy(
        savedMoshKey = "sessionkey==",
        savedMoshPort = 60001,
        savedMoshServerIp = "10.0.0.7",
    )

    @Test fun completeTupleIsEligible() {
        assertEquals(
            SavedMoshSession("10.0.0.7", 60001, "sessionkey=="),
            savedMoshSession(savedProfile, hasLiveSession = false),
        )
    }

    @Test fun optOutDisablesReattach() {
        assertEquals(null, savedMoshSession(savedProfile.copy(moshReconnectToExisting = false), hasLiveSession = false))
    }

    @Test fun liveSessionBlocksReattach() {
        // A second transport on the same key would fight the live tab.
        assertEquals(null, savedMoshSession(savedProfile, hasLiveSession = true))
    }

    @Test fun incompleteTupleIsIneligible() {
        assertEquals(null, savedMoshSession(savedProfile.copy(savedMoshKey = null), hasLiveSession = false))
        assertEquals(null, savedMoshSession(savedProfile.copy(savedMoshKey = ""), hasLiveSession = false))
        assertEquals(null, savedMoshSession(savedProfile.copy(savedMoshPort = null), hasLiveSession = false))
        assertEquals(null, savedMoshSession(savedProfile.copy(savedMoshPort = 0), hasLiveSession = false))
        assertEquals(null, savedMoshSession(savedProfile.copy(savedMoshServerIp = null), hasLiveSession = false))
    }
}
