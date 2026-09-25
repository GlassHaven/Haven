package sh.haven.app.agent

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.agent.ConsentLevel
import sh.haven.core.mcp.McpError
import sh.haven.core.openai.AiProtocol
import sh.haven.core.openai.AiRoute
import sh.haven.core.openai.ChatImage
import sh.haven.core.openai.OpenAiClient
import sh.haven.core.tunnel.TunnelResolver
import sh.haven.core.openai.ChatMessage
import sh.haven.core.openai.OpenAiSessionManager

/**
 * The OpenAI-endpoint MCP tools (Layer E, mirroring [MailToolProvider]):
 * `openai_list_models` (cached from the connect-time /v1/models probe) and
 * `openai_chat` (non-streaming one-shot; the UI's streaming path is not
 * reachable from MCP and chat is never persisted here). Both require the
 * profile to be CONNECTED first — connect_profile does the reachability +
 * auth probe.
 */
internal class OpenAiToolProvider(
    private val ctx: ToolContext,
    private val openAiSessionManager: OpenAiSessionManager,
    private val connectionRepository: ConnectionRepository,
    private val openAiClient: OpenAiClient,
    private val tunnelResolver: TunnelResolver,
) : ToolProvider {

    /** Max messages accepted by openai_chat — a transcript, not a memory. */
    private val maxMessages = 64

    override fun tools(): Map<String, ToolHandler> = linkedMapOf(
        "openai_list_models" to ToolHandler(
            description = "List the models a connected OPENAI profile advertises, from the connect-time GET /v1/models cache (connect_profile first; connect_profile re-verifies). Read-only.",
            inputSchema = objectSchema {
                string("profileId", "OPENAI connection profile id (from list_connections).", required = true)
            },
            consentLevel = ConsentLevel.ONCE_PER_SESSION,
            summarise = { args -> "List models on \"${ctx.profileLabel(args.optString("profileId"))}\"?" },
        ) { args -> listModels(args) },

        "openai_chat" to ToolHandler(
            description = "Send a non-streaming one-shot chat completion to a connected OPENAI profile and return the assistant reply as text. The endpoint decides what to do with the prompt — this is a real model call, so consent is required on every call. Never persisted; use the in-app chat screen for saved conversations. Pass `message` (single turn; the profile's system prompt stays the server's) or `messages` (an array of {role, content} for a multi-turn context, max $maxMessages). Optional imageBase64 + imageMimeType attach one image to the user turn for vision-capable models. Optional model (defaults to the profile's first advertised model), maxTokens, temperature.",
            inputSchema = objectSchema {
                string("profileId", "OPENAI connection profile id (from list_connections).", required = true)
                string("message", "Single user turn; mutually exclusive with messages.")
                objectArray(
                    "messages",
                    "Full transcript as {role, content} objects (role: user|assistant|system). Mutually exclusive with message.",
                ) {
                    string("role", "user | assistant | system; defaults to user.")
                    string("content")
                }
                string("imageBase64", "Optional raw base64 image (no data: prefix) attached to the single user turn for vision models. Max 5 MB encoded; jpeg, png, gif or webp. Only with `message`, not `messages`.")
                string("imageMimeType", "MIME type of imageBase64; default image/jpeg.")
                string("model", "Model id (from openai_list_models); defaults to the first advertised model.")
                integer("maxTokens", "Optional max_tokens for the completion.")
                property(
                    "temperature",
                    JSONObject().put("type", "number").put("description", "Optional sampling temperature (0–2)."),
                )
            },
            consentLevel = ConsentLevel.EVERY_CALL,
            summarise = { args ->
                val prompt = args.optString("message").ifBlank { "(messages array)" }
                "Send a chat completion to \"${ctx.profileLabel(args.optString("profileId"))}\" (\"${prompt.take(60)}\")?"
            },
        ) { args -> chat(args) },
    )

    private suspend fun listModels(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val models = openAiSessionManager.modelsForProfile(profileId)
        if (models.isEmpty()) {
            throw McpError(-32603, "Profile $profileId has no connected OpenAI session with models — call connect_profile first.")
        }
        return JSONObject().apply {
            put("profileId", profileId)
            put("count", models.size)
            put("models", JSONArray().apply {
                models.forEach { m ->
                    put(JSONObject().apply {
                        put("id", m.id)
                        if (m.ownedBy != null) put("ownedBy", m.ownedBy)
                    })
                }
            })
        }
    }

    private suspend fun chat(args: JSONObject): JSONObject {
        val profileId = args.optString("profileId").ifEmpty {
            throw McpError(-32602, "Missing required argument: profileId")
        }
        val session = openAiSessionManager.sessions.first().values
            .firstOrNull { it.profileId == profileId && it.status == OpenAiSessionManager.SessionState.Status.CONNECTED }
            ?: throw McpError(-32603, "Profile $profileId is not a connected OpenAI endpoint — call connect_profile first.")
        val profile = connectionRepository.getById(profileId)
            ?: throw McpError(-32603, "Profile $profileId not found")
        // Same route policy the connect path and the chat stream use
        // (AiRoute): a routed profile dials through its session's loopback
        // factory, a tunnel-configured profile through its tunnel, and a
        // configured-but-missing route refuses the dial rather than leaking
        // a direct call to the URL's host:port.
        val routed = AiRoute.isRouted(profile.aiRouteType, profile.aiRouteProfileId)
        val dial = AiRoute.dialFactory(
            routed = routed,
            routeFactory = session.routeSocketFactory,
            tunnelFactory = if (routed) null else tunnelResolver.socketFactory(profile),
            tunnelConfigured = if (routed) false else profile.tunnelConfigId != null,
        )
        val factory = when (dial) {
            is AiRoute.Dial.Refused -> throw McpError(-32603, dial.reason)
            is AiRoute.Dial.Via -> dial.factory
        }

        val messages = buildMessages(args)
        val model = args.optString("model").ifBlank { session.models.firstOrNull()?.id ?: "" }
        if (model.isBlank()) throw McpError(-32603, "No model available — the endpoint's /v1/models was empty at connect.")
        val maxTokens = if (args.has("maxTokens")) args.optInt("maxTokens").takeIf { it > 0 } else null
        val temperature = if (args.has("temperature")) args.optDouble("temperature").takeIf { !it.isNaN() } else null

        val reply = openAiClient.chatCompletion(
            openAiClient.buildClient(factory),
            session.baseUrl,
            profile.openaiPathPrefix?.ifBlank { null },
            profile.openaiApiKey?.ifBlank { null },
            model,
            messages,
            maxTokens,
            temperature,
            protocol = AiProtocol.fromStored(profile.aiProtocol),
        )
        return JSONObject().apply {
            put("profileId", profileId)
            put("model", model)
            put("reply", reply)
        }
    }

    private fun buildMessages(args: JSONObject): List<ChatMessage> {
        if (args.has("messages")) {
            val arr = args.optJSONArray("messages")
                ?: throw McpError(-32602, "messages must be an array of {role, content}")
            if (arr.length() == 0) throw McpError(-32602, "messages must not be empty")
            if (arr.length() > maxMessages) throw McpError(-32602, "messages supports at most $maxMessages entries")
            if (args.has("imageBase64")) {
                throw McpError(-32602, "imageBase64 attaches to a single user turn — use `message`, not `messages`")
            }
            return (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                val role = m.optString("role", "user").lowercase()
                if (role !in setOf("user", "assistant", "system")) {
                    throw McpError(-32602, "messages[$i].role must be user, assistant, or system")
                }
                ChatMessage(role = role, content = m.optString("content"))
            }
        }
        val single = args.optString("message")
        if (single.isBlank()) throw McpError(-32602, "Provide either message or messages")
        val image = parseImage(args)
        return listOf(ChatMessage(role = "user", content = single, images = listOfNotNull(image)))
    }

    /** Validated `imageBase64` + `imageMimeType` args, or null when no image was passed. */
    private fun parseImage(args: JSONObject): ChatImage? {
        val base64 = args.optString("imageBase64").trim()
        if (base64.isEmpty()) return null
        // 4 chars per 3 bytes; reject anything past the cap before decoding so
        // a runaway payload dies at validation, not inside the HTTP layer.
        if (base64.length > maxImageBase64Length) {
            throw McpError(-32602, "imageBase64 exceeds ${maxImageBytes / (1024 * 1024)} MB encoded")
        }
        val mime = args.optString("imageMimeType").ifBlank { "image/jpeg" }.lowercase()
        if (mime !in allowedImageMimeTypes) {
            throw McpError(-32602, "imageMimeType must be one of $allowedImageMimeTypes")
        }
        return ChatImage(mimeType = mime, base64 = base64)
    }

    private companion object {
        /** 5 MB of image payload keeps the one-shot call inside the MCP write timeout. */
        const val maxImageBytes = 5 * 1024 * 1024
        const val maxImageBase64Length = maxImageBytes / 3 * 4
        val allowedImageMimeTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    }
}