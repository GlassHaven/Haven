package sh.haven.core.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.haven.core.data.db.ChatConversationDao
import sh.haven.core.data.db.ChatMessageDao
import sh.haven.core.data.db.entities.ChatConversation
import sh.haven.core.data.db.entities.ChatMessage
import sh.haven.core.security.CredentialEncryption

/**
 * The `ENC:`-at-the-DAO-boundary contract for chat persistence: plaintext
 * never reaches the DAO, saved content round-trips through the repository,
 * un-readable ciphertext degrades to "" (never a crash), and a profile's
 * rows go messages-first so the sweep can't orphan them.
 *
 * DAOs are mocked (the SshIdentityResolutionTest pattern) — the encryption
 * itself runs for real against Robolectric's context, same as that test's
 * `CredentialEncryption.encrypt` use.
 */
@RunWith(RobolectricTestRunner::class)
class ChatRepositoryTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Test
    fun `saved conversation title is ENC-encrypted at the DAO boundary`() = runTest {
        val dao = mockk<ChatConversationDao>(relaxUnitFun = true)
        val captured = slot<sh.haven.core.data.db.entities.ChatConversation>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        ChatRepository(dao, mockk(relaxUnitFun = true), context)
            .saveConversation(ChatConversation(id = "c1", profileId = "p1", title = "private note"))

        val stored = captured.captured
        assertTrue("title must be ENC:-shaped, saw: ${stored.title}", stored.title.startsWith("ENC:"))
        assertTrue(stored.title != "private note")
    }

    @Test
    fun `conversation round-trips encrypted`() = runTest {
        val dao = mockk<ChatConversationDao>(relaxUnitFun = true)
        val captured = slot<sh.haven.core.data.db.entities.ChatConversation>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        val repo = ChatRepository(dao, mockk(), context)

        repo.saveConversation(ChatConversation(id = "c1", profileId = "p1", title = "private note"))
        // The DAO now holds the encrypted row; getById hands back exactly that.
        coEvery { dao.getById("c1") } returns captured.captured
        assertEquals("private note", repo.getConversation("c1")?.title)
    }

    @Test
    fun `saved message content round-trips encrypted`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxUnitFun = true)
        val captured = slot<sh.haven.core.data.db.entities.ChatMessage>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        coEvery { dao.getMessages("c1") } returns listOf(
            sh.haven.core.data.db.entities.ChatMessage(
                id = "m1", conversationId = "c1", role = "user",
                content = CredentialEncryption.encrypt(context, "hello there"),
            ),
        )
        val repo = ChatRepository(mockk(), dao, context)

        repo.saveMessage(ChatMessage(id = "m1", conversationId = "c1", role = "user", content = "hello there"))
        assertTrue(captured.captured.content.startsWith("ENC:"))
        assertEquals("hello there", repo.getMessages("c1").single().content)
    }

    @Test
    fun `message attachments round-trip encrypted`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxUnitFun = true)
        val captured = slot<sh.haven.core.data.db.entities.ChatMessage>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        val attachments = """[{"mimeType":"image/jpeg","base64":"AAAA"}]"""
        coEvery { dao.getMessages("c1") } returns listOf(
            sh.haven.core.data.db.entities.ChatMessage(
                id = "m2", conversationId = "c1", role = "user",
                content = CredentialEncryption.encrypt(context, "look"),
                attachments = CredentialEncryption.encrypt(context, attachments),
            ),
        )
        val repo = ChatRepository(mockk(), dao, context)

        repo.saveMessage(
            ChatMessage(id = "m2", conversationId = "c1", role = "user", content = "look", attachments = attachments),
        )
        assertTrue(captured.captured.content.startsWith("ENC:"))
        assertTrue("attachments must be ENC:-shaped, saw: ${captured.captured.attachments}",
            captured.captured.attachments!!.startsWith("ENC:"))
        val roundTripped = repo.getMessages("c1").single()
        assertEquals("look", roundTripped.content)
        assertEquals(attachments, roundTripped.attachments)
    }

    @Test
    fun `message without attachments keeps them null through the boundary`() = runTest {
        val dao = mockk<ChatMessageDao>(relaxUnitFun = true)
        val captured = slot<sh.haven.core.data.db.entities.ChatMessage>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        coEvery { dao.getMessages("c1") } returns listOf(
            sh.haven.core.data.db.entities.ChatMessage(
                id = "m3", conversationId = "c1", role = "assistant", content = "ok",
            ),
        )
        val repo = ChatRepository(mockk(), dao, context)

        repo.saveMessage(ChatMessage(id = "m3", conversationId = "c1", role = "assistant", content = "ok"))
        assertNull(captured.captured.attachments)
        assertNull(repo.getMessages("c1").single().attachments)
    }

    @Test
    fun `unreadable ciphertext degrades to empty string not a crash`() = runTest {
        val dao = mockk<ChatConversationDao>()
        coEvery { dao.getById("c1") } returns
            sh.haven.core.data.db.entities.ChatConversation(
                id = "c1", profileId = "p1",
                // "ENC:" + a body no Tink keyset can ever decrypt.
                title = "ENC:" + java.util.Base64.getEncoder().encodeToString(ByteArray(64)),
                createdAt = 1, savedAt = 2, updatedAt = 3,
            )
        val repo = ChatRepository(dao, mockk(), context)
        assertEquals("", repo.getConversation("c1")?.title)
    }

    @Test
    fun `profile sweep deletes messages before the conversation rows`() = runTest {
        val conversationDao = mockk<ChatConversationDao>(relaxUnitFun = true)
        val messageDao = mockk<ChatMessageDao>(relaxUnitFun = true)
        ChatRepository(conversationDao, messageDao, context).deleteByProfileId("p1")
        // Ordering matters: message ids are only reachable while the
        // conversation rows exist.
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            messageDao.deleteMessagesByProfileId("p1")
            conversationDao.deleteByProfileId("p1")
        }
    }

    @Test
    fun `deleting a conversation also drops its messages`() = runTest {
        val conversationDao = mockk<ChatConversationDao>(relaxUnitFun = true)
        val messageDao = mockk<ChatMessageDao>(relaxUnitFun = true)
        ChatRepository(conversationDao, messageDao, context).deleteConversation("c1")
        coVerify { messageDao.deleteByConversationId("c1") }
        coVerify { conversationDao.deleteById("c1") }
    }

    @Test
    fun `missing conversation resolves to null`() = runTest {
        val dao = mockk<ChatConversationDao>()
        coEvery { dao.getById("nope") } returns null
        assertNull(ChatRepository(dao, mockk(), context).getConversation("nope"))
    }
}