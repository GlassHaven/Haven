package sh.haven.feature.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.db.entities.ChatConversation
import sh.haven.core.data.db.entities.ChatMessage as SavedChatMessage
import sh.haven.core.data.images.ChatImagePrep
import sh.haven.core.data.repository.ChatRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.openai.ChatChunk
import sh.haven.core.openai.ChatImage
import sh.haven.core.openai.ChatMessage
import sh.haven.core.openai.ModelInfo
import sh.haven.core.openai.OpenAiClient
import sh.haven.core.openai.OpenAiSessionManager
import sh.haven.core.tunnel.TunnelResolver
import java.util.UUID
import javax.inject.Inject

/** One bubble in the chat transcript. [pending] = still streaming in. */
data class ChatUiMessage(
    val id: String,
    val role: String,
    val text: String,
    val pending: Boolean = false,
    val model: String? = null,
    /** Images attached to this turn (vision). Empty for plain text turns. */
    val images: List<ChatImage> = emptyList(),
)

/** An image queued in the composer, not yet sent. [preview] is a small thumbnail for the staged row. */
data class StagedImage(
    val id: String,
    val image: ChatImage,
    val preview: android.graphics.Bitmap?,
)

data class ChatUiState(
    val profileId: String? = null,
    val label: String = "",
    val models: List<ModelInfo> = emptyList(),
    val selectedModel: String? = null,
    val messages: List<ChatUiMessage> = emptyList(),
    val streaming: Boolean = false,
    /** Opt-in save: false = ephemeral (memory only), true = persisted encrypted. */
    val saveEnabled: Boolean = false,
    /** Images staged in the composer, awaiting the next send. */
    val staged: List<StagedImage> = emptyList(),
    val error: String? = null,
)

/**
 * The chat surface for a CONNECTED OPENAI profile. Ephemeral by default —
 * the transcript lives only in this ViewModel and is discarded with the
 * session. The save toggle opts in: the conversation row is created on the
 * spot, existing messages are back-filled, and every later message is
 * persisted through [ChatRepository] (encrypted at rest). Un-saving deletes
 * the rows.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val openAiSessionManager: OpenAiSessionManager,
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val openAiClient: OpenAiClient,
    private val tunnelResolver: TunnelResolver,
    private val chatAttachBroker: sh.haven.core.data.attach.ChatAttachBroker,
    private val chatRemoteReader: sh.haven.core.data.attach.ChatRemoteReader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var streamJob: Job? = null
    private var conversationId: String? = null

    /** Composer staging cap; bounds the request payload a single send can reach. */
    private val maxStagedImages = 4

    /** Remote-attach read cap, applied against the file's stat and its spooled bytes. */
    private val attachMaxBytes = 20L * 1024 * 1024

    /**
     * Point the screen at a profile (the OPENAI connect flow navigates here).
     * Re-attaching to a different profile resets the transcript — one active
     * conversation per profile-session.
     */
    fun attach(profileId: String) {
        if (_ui.value.profileId == profileId) return
        streamJob?.cancel()
        conversationId = null
        val session = openAiSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
        _ui.value = ChatUiState(
            profileId = profileId,
            label = session?.label ?: profileId,
            models = session?.models.orEmpty(),
            selectedModel = session?.models?.firstOrNull()?.id,
        )
    }

    fun selectModel(id: String) = _ui.update { it.copy(selectedModel = id) }

    /**
     * Decode + downscale a picked/captured image and stage it for the next
     * send. Undecodable URIs surface through the existing error slot.
     */
    fun stageImage(uri: Uri) {
        viewModelScope.launch {
            val prepared = ChatImagePrep.prepare(context, uri)
            if (prepared == null) {
                _ui.update { it.copy(error = context.getString(R.string.chat_attach_failed)) }
                return@launch
            }
            val staged = StagedImage(
                id = UUID.randomUUID().toString(),
                image = ChatImage(prepared.mimeType, prepared.base64),
                preview = ChatImagePrep.decodePreview(prepared.base64),
            )
            _ui.update { s ->
                if (s.staged.size >= maxStagedImages) s else s.copy(staged = s.staged + staged)
            }
        }
    }

    fun removeStaged(id: String) =
        _ui.update { s -> s.copy(staged = s.staged.filterNot { it.id == id }) }

    /**
     * Attach a file picked from the Files tab (the namespace-attach round
     * trip): arms the [ChatAttachBroker] pick and waits for the SFTP screen
     * to confirm a file, then reads it through the backend-agnostic
     * [ChatRemoteReader] (stat-capped) and stages it like any other image.
     * Cancel or a read failure surfaces through the error slot; the cap
     * guard in the staged insert below is the same one [stageImage] uses.
     */
    fun stageRemoteImage() {
        viewModelScope.launch {
            val pick = chatAttachBroker.awaitPick() ?: return@launch
            val bytes = try {
                chatRemoteReader.read(pick.profileId, pick.path, attachMaxBytes)
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        error = e.message
                            ?: context.getString(R.string.chat_attach_failed),
                    )
                }
                return@launch
            }
            val prepared = ChatImagePrep.prepare(bytes)
            if (prepared == null) {
                _ui.update { it.copy(error = context.getString(R.string.chat_attach_failed)) }
                return@launch
            }
            val staged = StagedImage(
                id = UUID.randomUUID().toString(),
                image = ChatImage(prepared.mimeType, prepared.base64),
                preview = ChatImagePrep.decodePreview(prepared.base64),
            )
            _ui.update { s ->
                if (s.staged.size >= maxStagedImages) s else s.copy(staged = s.staged + staged)
            }
        }
    }

    fun send(text: String) {
        val state = _ui.value
        val profileId = state.profileId ?: return
        val model = state.selectedModel ?: return
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && state.staged.isEmpty()) || state.streaming) return

        val stagedImages = state.staged.map { it.image }
        val history = state.messages
            .filter { !it.pending }
            .map { ChatMessage(role = it.role, content = it.text, images = it.images) } +
            ChatMessage("user", trimmed, stagedImages)
        val userMsg = ChatUiMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = trimmed,
            images = stagedImages,
        )
        val assistantId = UUID.randomUUID().toString()
        _ui.update {
            it.copy(
                messages = it.messages + userMsg +
                    ChatUiMessage(id = assistantId, role = "assistant", text = "", pending = true),
                staged = emptyList(),
                error = null,
            )
        }

        val conversation = ensureConversation(profileId)
        if (conversation != null) {
            viewModelScope.launch {
                chatRepository.saveMessage(
                    SavedChatMessage(
                        conversationId = conversation,
                        role = "user",
                        content = trimmed,
                        attachments = serializeAttachments(stagedImages),
                    ),
                )
            }
        }

        streamJob = viewModelScope.launch {
            val profile = connectionRepository.getById(profileId) ?: run {
                fail("Profile disappeared")
                return@launch
            }
            // Same fail-closed route contract as connect (AiRoute serves all
            // three dial sites): a routed profile reuses the loopback factory
            // the carrier's forward was established with, never re-resolving
            // — and a configured route that isn't there refuses the dial
            // rather than leaking a direct connection.
            val routed = sh.haven.core.openai.AiRoute.isRouted(
                profile.aiRouteType, profile.aiRouteProfileId,
            )
            val session = openAiSessionManager.getSessionsForProfile(profileId)
                .firstOrNull { it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
            val dial = sh.haven.core.openai.AiRoute.dialFactory(
                routed = routed,
                routeFactory = session?.routeSocketFactory,
                tunnelFactory = if (routed) null else tunnelResolver.socketFactory(profile),
                tunnelConfigured = if (routed) false else profile.tunnelConfigId != null,
            )
            val client = when (val d = dial) {
                is sh.haven.core.openai.AiRoute.Dial.Refused -> {
                    fail(d.reason)
                    return@launch
                }
                is sh.haven.core.openai.AiRoute.Dial.Via -> openAiClient.buildClient(d.factory)
            }
            try {
                openAiClient.chatCompletionStream(
                    client,
                    sessionBaseUrl(profileId),
                    profile.openaiPathPrefix?.ifBlank { null },
                    profile.openaiApiKey?.ifBlank { null },
                    model,
                    history,
                    protocol = sh.haven.core.openai.AiProtocol.fromStored(profile.aiProtocol),
                ).collect { chunk ->
                    applyChunk(assistantId, chunk)
                }
                finalizeMessage(assistantId, model)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
                finalizeMessage(assistantId, model)
            }
        }
    }

    /** Cancel the in-flight stream; whatever streamed in stays as the bubble text. */
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        val pending = _ui.value.messages.filter { it.pending }
        if (pending.isNotEmpty()) {
            _ui.update { s ->
                s.copy(
                    messages = s.messages.map { if (it.pending) it.copy(pending = false) else it },
                    streaming = false,
                )
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    /**
     * Toggle the save opt-in. Turning it on creates the encrypted conversation
     * row and back-fills everything already in the transcript; turning it off
     * deletes the rows (the in-memory transcript is untouched).
     */
    fun toggleSave() {
        val state = _ui.value
        val profileId = state.profileId ?: return
        if (!state.saveEnabled) {
            val conversation = ensureConversation(profileId, force = true)
            if (conversation == null) return
            viewModelScope.launch {
                state.messages.forEach { m ->
                    chatRepository.saveMessage(
                        SavedChatMessage(
                            conversationId = conversation,
                            role = m.role,
                            content = m.text,
                            model = m.model,
                            attachments = serializeAttachments(m.images),
                        ),
                    )
                }
            }
            _ui.update { it.copy(saveEnabled = true) }
        } else {
            val id = conversationId
            if (id != null) {
                viewModelScope.launch { chatRepository.deleteConversation(id) }
            }
            conversationId = null
            _ui.update { it.copy(saveEnabled = false) }
        }
    }

    /**
     * The conversation row, created lazily when the first message is persisted
     * after save is enabled. Null while ephemeral (or before a title exists).
     */
    private fun ensureConversation(profileId: String, force: Boolean = false): String? {
        conversationId?.let { return it }
        val state = _ui.value
        if (!state.saveEnabled && !force) return null
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            chatRepository.saveConversation(
                ChatConversation(
                    id = id,
                    profileId = profileId,
                    title = state.messages.firstOrNull { it.role == "user" }?.text?.take(80) ?: "Chat",
                ),
            )
        }
        conversationId = id
        return id
    }

    private fun applyChunk(assistantId: String, chunk: ChatChunk) {
        _ui.update { s ->
            val messages = s.messages.map { m ->
                if (m.id == assistantId) m.copy(text = m.text + chunk.delta) else m
            }
            s.copy(messages = messages, streaming = true)
        }
    }

    private fun finalizeMessage(assistantId: String, model: String) {
        streamJob = null
        val assistant = _ui.value.messages.lastOrNull { it.id == assistantId }
        _ui.update { s ->
            s.copy(
                messages = s.messages.map { m ->
                    if (m.id == assistantId) m.copy(pending = false, model = model) else m
                },
                streaming = false,
            )
        }
        // Persist the completed assistant bubble; the conversation id is only
        // set when saving is on, so ephemeral chats stop here.
        val conversation = conversationId
        if (conversation != null && assistant != null && assistant.text.isNotBlank()) {
            viewModelScope.launch {
                chatRepository.saveMessage(
                    SavedChatMessage(
                        conversationId = conversation,
                        role = "assistant",
                        content = assistant.text,
                        model = model,
                    ),
                )
            }
        }
    }

    private fun fail(message: String) {
        streamJob = null
        _ui.update { s ->
            s.copy(
                streaming = false,
                messages = s.messages.map { if (it.pending) it.copy(pending = false) else it },
                error = message,
            )
        }
    }

    /** Base URL of the connected session for this profile (empty when absent). */
    private fun sessionBaseUrl(profileId: String): String =
        openAiSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
            ?.baseUrl.orEmpty()

    /** JSON array of `{mimeType, base64}` for the attachments column; null for plain text turns. */
    private fun serializeAttachments(images: List<ChatImage>): String? {
        if (images.isEmpty()) return null
        return JSONArray().apply {
            images.forEach { img ->
                put(JSONObject().put("mimeType", img.mimeType).put("base64", img.base64))
            }
        }.toString()
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}