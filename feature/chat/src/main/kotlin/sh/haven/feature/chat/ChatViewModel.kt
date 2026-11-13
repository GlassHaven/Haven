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
import sh.haven.core.data.preferences.ChatMcpServerConfig
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ChatRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.openai.ChatChunk
import sh.haven.core.openai.ChatImage
import sh.haven.core.openai.ChatMessage
import sh.haven.core.openai.ChatToolCall
import sh.haven.core.openai.ExternalMcpClient
import sh.haven.core.openai.ModelInfo
import sh.haven.core.openai.OpenAiClient
import sh.haven.core.openai.OpenAiSessionManager
import sh.haven.core.tunnel.TunnelResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    val activeConversationId: String? = null,
    val savedConversations: List<ChatConversation> = emptyList(),
    /** External streamable-HTTP MCP servers configured for tool execution (#807). */
    val mcpServers: List<ChatMcpServerConfig> = emptyList(),
    /** Summary of the external MCP tool call currently running, if any (#807). */
    val activeToolCallSummary: String? = null,
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
class ChatViewModel private constructor(
    private val openAiSessionManager: OpenAiSessionManager,
    private val connectionRepository: ConnectionRepository,
    private val chatRepository: ChatRepository,
    private val openAiClient: OpenAiClient,
    private val tunnelResolver: TunnelResolver,
    private val chatAttachBroker: sh.haven.core.data.attach.ChatAttachBroker,
    private val chatRemoteReader: sh.haven.core.data.attach.ChatRemoteReader,
    private val userPreferencesRepository: UserPreferencesRepository?,
    private val context: Context,
    unusedMarker: Boolean,
) : ViewModel() {

    @Inject
    constructor(
        openAiSessionManager: OpenAiSessionManager,
        connectionRepository: ConnectionRepository,
        chatRepository: ChatRepository,
        openAiClient: OpenAiClient,
        tunnelResolver: TunnelResolver,
        chatAttachBroker: sh.haven.core.data.attach.ChatAttachBroker,
        chatRemoteReader: sh.haven.core.data.attach.ChatRemoteReader,
        userPreferencesRepository: UserPreferencesRepository,
        @ApplicationContext context: Context,
    ) : this(
        openAiSessionManager = openAiSessionManager,
        connectionRepository = connectionRepository,
        chatRepository = chatRepository,
        openAiClient = openAiClient,
        tunnelResolver = tunnelResolver,
        chatAttachBroker = chatAttachBroker,
        chatRemoteReader = chatRemoteReader,
        userPreferencesRepository = userPreferencesRepository,
        context = context,
        unusedMarker = true,
    )

    /** Secondary constructor kept for unit tests that do not exercise MCP prefs. */
    constructor(
        openAiSessionManager: OpenAiSessionManager,
        connectionRepository: ConnectionRepository,
        chatRepository: ChatRepository,
        openAiClient: OpenAiClient,
        tunnelResolver: TunnelResolver,
        chatAttachBroker: sh.haven.core.data.attach.ChatAttachBroker,
        chatRemoteReader: sh.haven.core.data.attach.ChatRemoteReader,
        context: Context,
    ) : this(
        openAiSessionManager = openAiSessionManager,
        connectionRepository = connectionRepository,
        chatRepository = chatRepository,
        openAiClient = openAiClient,
        tunnelResolver = tunnelResolver,
        chatAttachBroker = chatAttachBroker,
        chatRemoteReader = chatRemoteReader,
        userPreferencesRepository = null,
        context = context,
        unusedMarker = false,
    )

    private data class McpClientCacheKey(
        val url: String,
        val bearerToken: String?,
        val tunnelConfigId: String?,
    )

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var streamJob: Job? = null
    private var conversationsJob: Job? = null
    private var mcpServersJob: Job? = null
    private var conversationId: String? = null
    private val mcpClients = ConcurrentHashMap<McpClientCacheKey, ExternalMcpClient>()

    /** Composer staging cap; bounds the request payload a single send can reach. */
    private val maxStagedImages = 4

    /** Remote-attach read cap, applied against the file's stat and its spooled bytes. */
    private val attachMaxBytes = 20L * 1024 * 1024

    /** Maximum MCP tool-call roundtrips within a single user send (#807). */
    private val maxToolRounds = 8

    init {
        val prefs = userPreferencesRepository
        if (prefs != null) {
            mcpServersJob = viewModelScope.launch {
                runCatching {
                    prefs.chatMcpServers.collect { servers ->
                        _ui.update { it.copy(mcpServers = servers) }
                    }
                }
            }
        }
    }

    /**
     * Point the screen at a profile (the OPENAI connect flow navigates here).
     * Re-attaching to a different profile resets the transcript — one active
     * conversation per profile-session.
     */
    fun attach(profileId: String) {
        if (_ui.value.profileId == profileId) return
        streamJob?.cancel()
        conversationsJob?.cancel()
        conversationId = null
        val session = openAiSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
        _ui.update { current ->
            ChatUiState(
                profileId = profileId,
                label = session?.label ?: profileId,
                models = session?.models.orEmpty(),
                selectedModel = session?.models?.firstOrNull()?.id,
                mcpServers = current.mcpServers,
            )
        }
        conversationsJob = viewModelScope.launch {
            runCatching {
                chatRepository.observeAll().collect { all ->
                    _ui.update { s ->
                        s.copy(savedConversations = all.filter { it.profileId == profileId })
                    }
                }
            }
        }
    }

    fun selectModel(id: String) = _ui.update { it.copy(selectedModel = id) }

    fun toggleModelFlavour(suffix: String) = _ui.update { s ->
        val current = s.selectedModel ?: return@update s
        s.copy(selectedModel = ChatModelFlavours.toggleFlavour(current, suffix))
    }

    fun addMcpServer(name: String, url: String, bearerToken: String?) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
            !trimmedUrl.startsWith("https://", ignoreCase = true)
        ) {
            _ui.update { it.copy(error = context.getString(R.string.chat_mcp_invalid_url)) }
            return
        }
        val entry = ChatMcpServerConfig(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { trimmedUrl },
            url = trimmedUrl,
            bearerToken = bearerToken?.trim()?.takeIf { it.isNotEmpty() },
            enabled = true,
        )
        val updated = _ui.value.mcpServers + entry
        _ui.update { it.copy(mcpServers = updated) }
        userPreferencesRepository?.let { prefs ->
            viewModelScope.launch { prefs.setChatMcpServers(updated) }
        }
    }

    fun toggleMcpServer(id: String, enabled: Boolean) {
        val updated = _ui.value.mcpServers.map { if (it.id == id) it.copy(enabled = enabled) else it }
        _ui.update { it.copy(mcpServers = updated) }
        userPreferencesRepository?.let { prefs ->
            viewModelScope.launch { prefs.setChatMcpServers(updated) }
        }
    }

    fun removeMcpServer(id: String) {
        val removed = _ui.value.mcpServers.filter { it.id == id }
        removed.forEach { s ->
            mcpClients.keys.removeIf { it.url == s.url }
        }
        val updated = _ui.value.mcpServers.filterNot { it.id == id }
        _ui.update { it.copy(mcpServers = updated) }
        userPreferencesRepository?.let { prefs ->
            viewModelScope.launch { prefs.setChatMcpServers(updated) }
        }
    }

    /** Start a fresh ephemeral session on the attached profile (#807). */
    fun newConversation() {
        streamJob?.cancel()
        streamJob = null
        conversationId = null
        _ui.update { s ->
            s.copy(
                messages = emptyList(),
                streaming = false,
                saveEnabled = false,
                activeConversationId = null,
                activeToolCallSummary = null,
                staged = emptyList(),
                error = null,
            )
        }
    }

    /** Load a previously saved conversation into the active transcript (#807). */
    fun loadConversation(id: String) {
        val profileId = _ui.value.profileId ?: return
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            val conv = chatRepository.getConversation(id) ?: return@launch
            if (conv.profileId != profileId) return@launch
            val savedMessages = chatRepository.getMessages(id)
            val uiMessages = savedMessages.map { m ->
                ChatUiMessage(
                    id = m.id,
                    role = m.role,
                    text = m.content,
                    pending = false,
                    model = m.model,
                    images = deserializeAttachments(m.attachments),
                )
            }
            val lastModel = savedMessages.lastOrNull { !it.model.isNullOrBlank() }?.model
            conversationId = id
            _ui.update { s ->
                s.copy(
                    messages = uiMessages,
                    streaming = false,
                    saveEnabled = true,
                    activeConversationId = id,
                    selectedModel = lastModel ?: s.selectedModel,
                    activeToolCallSummary = null,
                    staged = emptyList(),
                    error = null,
                )
            }
        }
    }

    /** Delete a saved conversation; if it was the active one, revert to unsaved (#807). */
    fun deleteSavedConversation(id: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
        }
        if (conversationId == id) {
            conversationId = null
            _ui.update { it.copy(saveEnabled = false, activeConversationId = null) }
        }
    }

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
        val workingHistory = (
            state.messages
                .filter { !it.pending }
                .map { ChatMessage(role = it.role, content = it.text, images = it.images) } +
                ChatMessage("user", trimmed, stagedImages)
            ).toMutableList()

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
                streaming = true,
                activeToolCallSummary = null,
                staged = emptyList(),
                error = null,
            )
        }

        val hadUserMessage = state.messages.any { it.role == "user" }
        val conversation = ensureConversation(profileId)
        if (conversation != null) {
            viewModelScope.launch {
                if (!hadUserMessage && trimmed.isNotEmpty()) {
                    chatRepository.rename(conversation, trimmed.take(80))
                }
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

        val enabledMcpServers = state.mcpServers.filter { it.enabled }

        streamJob = viewModelScope.launch {
            val profile = connectionRepository.getById(profileId) ?: run {
                fail("Profile disappeared")
                return@launch
            }
            val routed = sh.haven.core.openai.AiRoute.isRouted(
                profile.aiRouteType, profile.aiRouteProfileId,
            )
            val session = openAiSessionManager.getSessionsForProfile(profileId)
                .firstOrNull { it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
            val tunnelFactory = if (routed) null else tunnelResolver.socketFactory(profile)
            val dial = sh.haven.core.openai.AiRoute.dialFactory(
                routed = routed,
                routeFactory = session?.routeSocketFactory,
                tunnelFactory = tunnelFactory,
                tunnelConfigured = if (routed) false else profile.tunnelConfigId != null,
            )
            val client = when (val d = dial) {
                is sh.haven.core.openai.AiRoute.Dial.Refused -> {
                    fail(d.reason)
                    return@launch
                }
                is sh.haven.core.openai.AiRoute.Dial.Via -> openAiClient.buildClient(d.factory)
            }
            val protocol = sh.haven.core.openai.AiProtocol.fromStored(profile.aiProtocol)
            val baseUrl = sessionBaseUrl(profileId)
            val pathPrefix = profile.openaiPathPrefix?.ifBlank { null }
            val apiKey = profile.openaiApiKey?.ifBlank { null }

            try {
                val mcpHttpClient by lazy {
                    openAiClient.buildClient(if (routed) null else tunnelFactory)
                }
                fun clientForServer(srv: ChatMcpServerConfig): ExternalMcpClient {
                    val key = McpClientCacheKey(
                        url = srv.url,
                        bearerToken = srv.bearerToken,
                        tunnelConfigId = if (routed) null else profile.tunnelConfigId,
                    )
                    return mcpClients.getOrPut(key) {
                        ExternalMcpClient(
                            httpClient = mcpHttpClient,
                            endpointUrl = srv.url,
                            bearerToken = srv.bearerToken,
                        )
                    }
                }

                val boundTools = if (enabledMcpServers.isEmpty()) {
                    emptyMap()
                } else {
                    val discovered = enabledMcpServers.map { srv ->
                        try {
                            srv to clientForServer(srv).listTools()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            throw IllegalStateException("${srv.name}: ${e.message ?: e.javaClass.simpleName}", e)
                        }
                    }
                    ChatMcpToolRouter.bindTools(discovered)
                }
                val toolSpecs = boundTools.values.map { it.spec }

                var round = 0
                while (round < maxToolRounds) {
                    val roundText = StringBuilder()
                    val roundToolCalls = mutableListOf<ChatToolCall>()

                    openAiClient.chatCompletionStream(
                        client = client,
                        base = baseUrl,
                        prefix = pathPrefix,
                        apiKey = apiKey,
                        model = model,
                        messages = workingHistory,
                        protocol = protocol,
                        tools = toolSpecs,
                    ).collect { chunk ->
                        if (chunk.delta.isNotEmpty()) {
                            roundText.append(chunk.delta)
                            applyChunk(assistantId, chunk)
                        }
                        if (chunk.toolCalls.isNotEmpty()) {
                            roundToolCalls += chunk.toolCalls
                        }
                    }

                    if (roundToolCalls.isEmpty() || boundTools.isEmpty()) {
                        break
                    }

                    workingHistory += ChatMessage(
                        role = "assistant",
                        content = roundText.toString(),
                        toolCalls = roundToolCalls,
                    )
                    if (roundText.isNotEmpty() && !roundText.endsWith("\n")) {
                        applyChunk(assistantId, ChatChunk(delta = "\n\n"))
                    }

                    for (tc in roundToolCalls) {
                        val bound = boundTools[tc.name]
                        if (bound == null) {
                            workingHistory += ChatMessage(
                                role = "tool",
                                content = "Unknown tool: ${tc.name}",
                                toolCallId = tc.id,
                                toolName = tc.name,
                            )
                            continue
                        }
                        _ui.update {
                            it.copy(activeToolCallSummary = "${bound.server.name}: ${bound.remoteToolName}")
                        }
                        val argsObj = runCatching { JSONObject(tc.argumentsJson) }.getOrElse { JSONObject() }
                        val resultText = try {
                            val mcpRes = clientForServer(bound.server).callTool(bound.remoteToolName, argsObj)
                            if (mcpRes.isError) "Error: ${mcpRes.content}" else mcpRes.content
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "MCP error: ${e.message ?: e.javaClass.simpleName}"
                        }
                        workingHistory += ChatMessage(
                            role = "tool",
                            content = resultText,
                            toolCallId = tc.id,
                            toolName = tc.name,
                        )
                    }
                    _ui.update { it.copy(activeToolCallSummary = null) }
                    round++
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
        _ui.update { s ->
            s.copy(
                messages = if (pending.isNotEmpty()) {
                    s.messages.map { if (it.pending) it.copy(pending = false) else it }
                } else {
                    s.messages
                },
                streaming = false,
                activeToolCallSummary = null,
            )
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
            _ui.update { it.copy(saveEnabled = true, activeConversationId = conversation) }
        } else {
            val id = conversationId
            if (id != null) {
                viewModelScope.launch { chatRepository.deleteConversation(id) }
            }
            conversationId = null
            _ui.update { it.copy(saveEnabled = false, activeConversationId = null) }
        }
    }

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
                activeToolCallSummary = null,
            )
        }
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
                activeToolCallSummary = null,
                messages = s.messages.map { if (it.pending) it.copy(pending = false) else it },
                error = message,
            )
        }
    }

    private fun sessionBaseUrl(profileId: String): String =
        openAiSessionManager.getSessionsForProfile(profileId)
            .firstOrNull { it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
            ?.baseUrl.orEmpty()

    private fun serializeAttachments(images: List<ChatImage>): String? {
        if (images.isEmpty()) return null
        return JSONArray().apply {
            images.forEach { img ->
                put(JSONObject().put("mimeType", img.mimeType).put("base64", img.base64))
            }
        }.toString()
    }

    private fun deserializeAttachments(raw: String?): List<ChatImage> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val mime = obj.optString("mimeType").takeIf { it.isNotBlank() } ?: continue
                    val b64 = obj.optString("base64").takeIf { it.isNotBlank() } ?: continue
                    add(ChatImage(mime, b64))
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        streamJob?.cancel()
        conversationsJob?.cancel()
        mcpServersJob?.cancel()
        super.onCleared()
    }
}
