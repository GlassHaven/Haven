package sh.haven.app

import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.app.agent.TerminalInputQueue
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject

class HavenAppColdStartTest {

    @Test
    fun `HavenApp heavy singleton fields are injected via dagger Lazy`() {
        val eagerAllowed = setOf(
            "preferencesRepository",
            "workerFactory",
            "sshTerminalEmulatorOwner",
            "launcherShortcutManager",
            "agentConsentManager",
        )
        val injectedFields = HavenApp::class.java.declaredFields
            .filter { it.isAnnotationPresent(Inject::class.java) }

        assertTrue("Expected @Inject fields on HavenApp", injectedFields.isNotEmpty())

        val eagerHeavy = injectedFields
            .filter { it.name !in eagerAllowed && it.type != Lazy::class.java }
            .map { "${it.name}:${it.type.simpleName}" }

        assertEquals(
            "Heavy @Singleton collaborators on HavenApp must use dagger.Lazy<T> to avoid cold-start instantiation",
            emptyList<String>(),
            eagerHeavy,
        )
    }

    @Test
    fun `TerminalInputQueue does not spin poll loop until enqueue and parks when drained`() {
        val sshSessionManager = mockk<SshSessionManager>(relaxed = true) {
            every { agentScrollbackTotalBytes(any()) } returns 0L
        }
        val consentManager = mockk<AgentConsentManager>(relaxed = true)
        val queue = TerminalInputQueue(sshSessionManager, consentManager)

        val scopeField = TerminalInputQueue::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = scopeField.get(queue) as CoroutineScope
        val parentJob = scope.coroutineContext[Job]!!

        assertEquals(
            "Poll loop must not start eagerly in TerminalInputQueue.<init> when no input is queued",
            0,
            parentJob.children.count { it.isActive },
        )

        val id = queue.enqueue(
            sessionId = "sess-1",
            text = "yes",
            promptPattern = "\\$ ",
            timeoutSeconds = 5,
        )
        assertEquals(
            "Poll loop must start lazily on first enqueue",
            1,
            parentJob.children.count { it.isActive },
        )

        assertTrue(queue.cancel(id))
        val deadline = System.currentTimeMillis() + 1_500L
        while (parentJob.children.count { it.isActive } > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals(
            "Poll loop must exit cleanly once the queue drains",
            0,
            parentJob.children.count { it.isActive },
        )
    }
}
