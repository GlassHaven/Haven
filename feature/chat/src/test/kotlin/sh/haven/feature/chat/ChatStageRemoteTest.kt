package sh.haven.feature.chat

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The chat Files-attach round trip: broker pick → backend read → image
 * staging, with the failure paths (read error, undecodable bytes, cancelled
 * pick) landing in the existing error slot instead of staging garbage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStageRemoteTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var broker: sh.haven.core.data.attach.ChatAttachBroker
    private lateinit var reader: sh.haven.core.data.attach.ChatRemoteReader
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(sh.haven.core.data.images.ChatImagePrep)
        coEvery {
            sh.haven.core.data.images.ChatImagePrep.prepare(any<ByteArray>())
        } returns sh.haven.core.data.images.ChatImagePrep.PreparedImage("image/jpeg", "QUJD")
        every {
            sh.haven.core.data.images.ChatImagePrep.decodePreview(any(), any())
        } returns null
        broker = sh.haven.core.data.attach.ChatAttachBroker()
        reader = mockk()
        viewModel = ChatViewModel(
            openAiSessionManager = mockk(),
            connectionRepository = mockk(),
            chatRepository = mockk(),
            openAiClient = mockk(),
            tunnelResolver = mockk(),
            chatAttachBroker = broker,
            chatRemoteReader = reader,
            context = mockk {
                every { getString(any()) } returns "attach failed"
            },
        )
    }

    @After
    fun teardown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun ui() = viewModel.ui.value

    @Test
    fun `pick then read then stage lands in the composer`() {
        coEvery { reader.read("prof1", "/srv/img.png", 20L * 1024 * 1024) } returns byteArrayOf(1, 2, 3)

        viewModel.stageRemoteImage()
        // armed: pick pending, nothing staged yet
        assertTrue(broker.pending.value)
        assertEquals(0, ui().staged.size)

        broker.confirmPick("prof1", "/srv/img.png", "img.png", 3)

        assertEquals(1, ui().staged.size)
        assertEquals("image/jpeg", ui().staged[0].image.mimeType)
        assertEquals("QUJD", ui().staged[0].image.base64)
        assertNull(ui().error)
        assertEquals(false, broker.pending.value)
    }

    @Test
    fun `reader failure surfaces in the error slot`() {
        coEvery {
            reader.read(any(), any(), any())
        } throws IllegalStateException("No connected backend for the picked file's profile")

        viewModel.stageRemoteImage()
        broker.confirmPick("prof1", "/srv/x.png", "x.png", 1)

        assertEquals(
            "No connected backend for the picked file's profile",
            ui().error,
        )
        assertEquals(0, ui().staged.size)
    }

    @Test
    fun `undecodable bytes surface in the error slot`() {
        coEvery { reader.read(any(), any(), any()) } returns byteArrayOf(1, 2, 3)
        coEvery {
            sh.haven.core.data.images.ChatImagePrep.prepare(any<ByteArray>())
        } returns null

        viewModel.stageRemoteImage()
        broker.confirmPick("prof1", "/srv/x.png", "x.png", 1)

        assertEquals("attach failed", ui().error)
        assertEquals(0, ui().staged.size)
    }

    @Test
    fun `cancelled pick leaves staging untouched and clears pending`() {
        viewModel.stageRemoteImage()
        assertTrue(broker.pending.value)

        broker.cancelPick()

        assertEquals(0, ui().staged.size)
        assertNull(ui().error)
        assertEquals(false, broker.pending.value)
    }
}