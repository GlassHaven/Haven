package sh.haven.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import sh.haven.core.data.db.ChatConversationDao
import sh.haven.core.data.db.ChatMessageDao
import sh.haven.core.data.db.entities.ChatConversation
import sh.haven.core.data.db.entities.ChatMessage
import sh.haven.core.security.CredentialEncryption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for opt-in-saved chat conversations. Everything crossing the
 * DAO boundary is `ENC:`-encrypted per string via [CredentialEncryption]
 * (Tink AEAD) — same boundary ConnectionRepository holds for profile
 * credentials, so a conversation dumped from the DB without Haven's keystore
 * is unreadable. Ephemeral conversations never reach this class.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val conversationDao: ChatConversationDao,
    private val messageDao: ChatMessageDao,
    @ApplicationContext private val context: Context,
) {
    fun observeAll(): Flow<List<ChatConversation>> =
        conversationDao.observeAll().map { list -> list.map { decryptConversation(it) } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeMessages(conversationId).map { messages -> messages.map { decryptMessage(it) } }

    suspend fun getConversation(id: String): ChatConversation? =
        conversationDao.getById(id)?.let { decryptConversation(it) }

    suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messageDao.getMessages(conversationId).map { decryptMessage(it) }

    suspend fun saveConversation(conversation: ChatConversation) =
        conversationDao.upsert(encryptConversation(conversation))

    suspend fun rename(id: String, title: String, updatedAt: Long = System.currentTimeMillis()) {
        conversationDao.rename(
            id,
            CredentialEncryption.encrypt(context, title),
            updatedAt,
        )
    }

    suspend fun saveMessage(message: ChatMessage) =
        messageDao.upsert(encryptMessage(message))

    suspend fun deleteConversation(id: String) {
        messageDao.deleteByConversationId(id)
        conversationDao.deleteById(id)
    }

    suspend fun deleteByProfileId(profileId: String) {
        // Messages first: once the conversation rows are gone their ids are
        // unreachable, so a conversation-first order would orphan them.
        messageDao.deleteMessagesByProfileId(profileId)
        conversationDao.deleteByProfileId(profileId)
    }

    private fun encryptConversation(conversation: ChatConversation) = conversation.copy(
        title = CredentialEncryption.encrypt(context, conversation.title),
    )

    private fun decryptConversation(conversation: ChatConversation) = conversation.copy(
        title = CredentialEncryption.decryptOrNull(context, conversation.title) ?: "",
    )

    private fun encryptMessage(message: ChatMessage) = message.copy(
        content = CredentialEncryption.encrypt(context, message.content),
        attachments = message.attachments?.let { CredentialEncryption.encrypt(context, it) },
    )

    private fun decryptMessage(message: ChatMessage) = message.copy(
        content = CredentialEncryption.decryptOrNull(context, message.content) ?: "",
        attachments = message.attachments?.let { CredentialEncryption.decryptOrNull(context, it) },
    )
}