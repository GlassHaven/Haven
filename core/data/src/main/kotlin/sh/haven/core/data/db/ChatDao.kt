package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.ChatConversation
import sh.haven.core.data.db.entities.ChatMessage

@Dao
interface ChatConversationDao {

    @Query("SELECT * FROM chat_conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatConversation>>

    @Query("SELECT * FROM chat_conversations WHERE id = :id")
    suspend fun getById(id: String): ChatConversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ChatConversation)

    @Query("UPDATE chat_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM chat_conversations WHERE profileId = :profileId")
    suspend fun deleteByProfileId(profileId: String)
}

@Dao
interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessages(conversationId: String): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)

    /** Profile deletion's message sweep: everything belonging to its conversations. */
    @Query(
        "DELETE FROM chat_messages WHERE conversationId IN " +
            "(SELECT id FROM chat_conversations WHERE profileId = :profileId)",
    )
    suspend fun deleteMessagesByProfileId(profileId: String)
}