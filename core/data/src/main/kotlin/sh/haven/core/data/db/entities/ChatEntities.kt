package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A chat conversation bound to an OPENAI connection profile. Ephemeral
 * conversations (the default) never touch this table; a conversation only
 * exists here after the user opts in to saving it from the chat screen.
 *
 * Message bodies are encrypted at rest (`ENC:`+Base64 via
 * `CredentialEncryption`) — the encrypt/decrypt boundary is
 * `ChatRepository`, mirroring how `sshPassword` is handled on profiles.
 */
@Entity(tableName = "chat_conversations")
data class ChatConversation(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    /** Display title; derived from the first user message until renamed. */
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** When the user opted in to saving (0 = created unsaved — see below). */
    val savedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One message in a saved [ChatConversation]. [content] is encrypted at rest.
 */
@Entity(tableName = "chat_messages", indices = [Index("conversationId")])
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    /** "user" / "assistant" / "system". */
    val role: String,
    /** Plaintext in memory; `ENC:`-encrypted in the row. */
    val content: String,
    /** Model that produced an assistant message; null for user/system. */
    val model: String? = null,
    /**
     * Attached images (vision) as a JSON array of `{mimeType, base64}` —
     * plaintext in memory; the whole serialized string is `ENC:`-encrypted in
     * the row, same boundary as [content]. Null for plain text turns.
     */
    val attachments: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)