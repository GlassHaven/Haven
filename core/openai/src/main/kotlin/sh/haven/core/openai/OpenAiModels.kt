package sh.haven.core.openai

/**
 * Types for the OpenAI-compatible endpoint client (`sh.haven.core.openai`).
 *
 * Deliberately minimal: only the shapes this codebase consumes
 * (`/v1/models`, `/v1/chat/completions` streaming + one-shot). Anything the
 * server sends beyond these fields is ignored, so servers like llama-server
 * and CLIProxyAPI that add extra properties keep working.
 */

data class ModelInfo(
    val id: String,
    val ownedBy: String? = null,
)

/**
 * One image attached to a chat turn: decoded, downscaled and base64-encoded
 * by the caller (see ChatImagePrep in :core:data). Payload builders serialize
 * it per protocol.
 */
data class ChatImage(
    /** e.g. "image/jpeg". */
    val mimeType: String,
    /** Raw base64 (no data: prefix). */
    val base64: String,
)

data class ChatMessage(
    /** "user" / "assistant" / "system". */
    val role: String,
    val content: String,
    /** Images attached to this turn (vision). Empty for plain text turns. */
    val images: List<ChatImage> = emptyList(),
)

/**
 * One increment of a streamed completion (or the whole completion for the
 * non-streaming fallback path — that chunk arrives with [finishReason] set).
 */
data class ChatChunk(
    /** Text delta for this chunk; empty for role-only keep-alives. */
    val delta: String,
    val finishReason: String? = null,
)

/**
 * Sealed error hierarchy so the connect flow and the chat screen can show the
 * right remediation (fix the key / check the URL / retry the network) without
 * parsing message strings.
 */
sealed class OpenAiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 401/403 — the endpoint rejected the API key (or wanted one and got none). */
    class Auth(message: String) : OpenAiException(message)

    /** 4xx other than auth — usually a wrong path prefix or unsupported request. */
    class Http(val code: Int, message: String) : OpenAiException(message)

    /** Transport failure: DNS, refused, timeout, tunnel down. */
    class Network(message: String, cause: Throwable? = null) : OpenAiException(message, cause)

    /** 2xx but the body is not a parseable OpenAI response. */
    class BadResponse(message: String) : OpenAiException(message)
}

/**
 * Result of a connect-time reachability + auth probe (`GET /v1/models`).
 */
sealed class VerifyResult {
    data class Ok(val models: List<ModelInfo>) : VerifyResult()
    data class Failure(val error: OpenAiException) : VerifyResult()
}