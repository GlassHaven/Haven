package sh.haven.core.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ProotInstallLogRepository
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class ColdStartInitTest {

    @Test
    fun `ProotManager constructor does not touch filesDir or cacheDir synchronously on caller thread`() {
        val callerThread = Thread.currentThread()
        val tmpBase = Files.createTempDirectory("proot-cold-start").toFile()
        val filesDir = File(tmpBase, "files").apply { mkdirs() }
        val cacheDir = File(tmpBase, "cache").apply { mkdirs() }
        val diskAccessThreads = CopyOnWriteArrayList<Thread>()

        val ctx = mockk<Context>(relaxed = true) {
            every { this@mockk.filesDir } answers {
                diskAccessThreads += Thread.currentThread()
                filesDir
            }
            every { this@mockk.cacheDir } answers {
                diskAccessThreads += Thread.currentThread()
                cacheDir
            }
        }
        try {
            val mgr = ProotManager(
                context = ctx,
                installLogRepository = mockk<ProotInstallLogRepository>(relaxed = true),
            )
            assertFalse(
                "ProotManager.<init> must offload filesystem migration/backfill/model I/O off caller thread",
                diskAccessThreads.contains(callerThread),
            )
            val jobField = ProotManager::class.java.declaredFields.firstOrNull { it.name == "startupInitJob" }
            assertFalse("ProotManager must expose startupInitJob", jobField == null)
            jobField!!.isAccessible = true
            val job = jobField.get(mgr) as kotlinx.coroutines.Job
            val deadline = System.currentTimeMillis() + 3_000L
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        } finally {
            forceDeleteRecursively(tmpBase)
        }
    }

    @Test
    fun `DesktopManager exposes background orphanReapJob instead of blocking caller thread`() {
        val tmpBase = Files.createTempDirectory("desktop-cold-start").toFile()
        val ctx = mockk<Context>(relaxed = true) {
            every { filesDir } returns File(tmpBase, "files").apply { mkdirs() }
            every { cacheDir } returns File(tmpBase, "cache").apply { mkdirs() }
        }
        try {
            val dm = DesktopManager(
                context = ctx,
                prootManager = mockk(relaxed = true),
                userPreferencesRepository = mockk<UserPreferencesRepository>(relaxed = true),
                umlDesktopProvider = javax.inject.Provider { mockk(relaxed = true) },
            )
            val jobField = DesktopManager::class.java.declaredFields.firstOrNull { it.name == "orphanReapJob" }
            assertFalse("DesktopManager must schedule killAllOrphanedXvnc asynchronously on orphanReapJob", jobField == null)
            jobField!!.isAccessible = true
            val job = jobField.get(dm) as kotlinx.coroutines.Job
            val deadline = System.currentTimeMillis() + 3_000L
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        } finally {
            forceDeleteRecursively(tmpBase)
        }
    }
}
