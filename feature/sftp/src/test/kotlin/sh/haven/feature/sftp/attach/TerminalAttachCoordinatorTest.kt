package sh.haven.feature.sftp.attach

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.feature.sftp.transport.RemoteFileTransport
import sh.haven.feature.sftp.transport.TransportSelector
import java.io.ByteArrayInputStream

/**
 * Terminal paperclip → "Send file" with a multi-select picker: the user picks
 * the destination folder ONCE for the whole batch, every file is uploaded into
 * it, and all the shell-quoted paths are pasted together in pick order.
 */
class TerminalAttachCoordinatorTest {

    private val transport = mockk<RemoteFileTransport>()
    private val transportSelector = mockk<TransportSelector>()
    private val connectionRepository = mockk<ConnectionRepository>()
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private val profile = ConnectionProfile(label = "Server", host = "server.example.com", username = "u")
    private lateinit var coordinator: TerminalAttachCoordinator

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(any()) } answers { ByteArrayInputStream(ByteArray(3)) }
        coEvery { connectionRepository.getById("p1") } returns profile
        every { transportSelector.resolve(profile) } returns TransportSelector.Resolution(transport)
        coEvery { transport.upload(any(), any(), any(), any()) } just runs
        coordinator = TerminalAttachCoordinator(transportSelector, mockk(relaxed = true), connectionRepository, context)
    }

    private fun file(name: String) =
        TerminalAttachCoordinator.AttachFile(mockk<Uri>(relaxed = true), name, 3L)

    /** Start [files] attaching, wait for the folder picker, pick [folder] (or cancel). */
    private suspend fun kotlinx.coroutines.CoroutineScope.attachInto(
        files: List<TerminalAttachCoordinator.AttachFile>,
        folder: String?,
        onRequest: (TerminalAttachCoordinator.AttachRequest) -> Unit = {},
    ): String? {
        val result = async { coordinator.attach(files, initialProfileId = "p1") }
        val request = coordinator.pendingRequest.first { it != null }!!
        onRequest(request)
        if (folder == null) coordinator.cancel()
        else coordinator.confirmFolder("p1", isRclone = false, rcloneRemote = null, folderPath = folder)
        return result.await()
    }

    @Test
    fun `batch uploads every file into the one chosen folder and pastes all paths`() = runTest {
        val pasted = attachInto(listOf(file("a.png"), file("b c.txt")), "/tmp/up/")

        assertEquals("'/tmp/up/a.png' '/tmp/up/b c.txt'", pasted)
        coVerifyOrder {
            transport.upload(any(), 3L, "/tmp/up/a.png", any())
            transport.upload(any(), 3L, "/tmp/up/b c.txt", any())
        }
    }

    @Test
    fun `folder banner names every picked file`() = runTest {
        var banner: String? = null
        attachInto(listOf(file("a.png"), file("b.txt")), "/tmp") { banner = it.fileName }

        assertEquals("a.png, b.txt", banner)
    }

    @Test
    fun `a single file still pastes just its own path`() = runTest {
        var banner: String? = null
        val pasted = attachInto(listOf(file("photo.jpg")), "/tmp") { banner = it.fileName }

        assertEquals("photo.jpg", banner)
        assertEquals("'/tmp/photo.jpg'", pasted)
    }

    @Test
    fun `a failed file is skipped and the rest are still pasted`() = runTest {
        coEvery { transport.upload(any(), any(), "/tmp/bad.bin", any()) } throws java.io.IOException("disk full")

        val pasted = attachInto(listOf(file("a.png"), file("bad.bin"), file("c.txt")), "/tmp")

        assertEquals("'/tmp/a.png' '/tmp/c.txt'", pasted)
    }

    @Test
    fun `nothing is pasted when every upload fails`() = runTest {
        coEvery { transport.upload(any(), any(), any(), any()) } throws java.io.IOException("offline")

        assertNull(attachInto(listOf(file("a.png"), file("b.png")), "/tmp"))
    }

    @Test
    fun `cancelling the folder pick uploads nothing`() = runTest {
        assertNull(attachInto(listOf(file("a.png"), file("b.png")), folder = null))
        coVerify(exactly = 0) { transport.upload(any(), any(), any(), any()) }
    }
}
