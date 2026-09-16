package sh.haven.core.openai

/**
 * Which wire protocol an OPENAI-type profile's endpoint speaks. The profile
 * stores the raw name (null/blank = [OPENAI]); this enum lives in :core:openai
 * because it owns the path/auth/payload branches in [OpenAiClient].
 */
enum class AiProtocol {
    /** OpenAI-compatible `/v1/chat/completions` + SSE (llama-server, CLIProxyAPI, vLLM, …). */
    OPENAI,

    /** Ollama's native `/api/tags` + `/api/chat` with NDJSON streaming. */
    OLLAMA,

    /** Anthropic Messages API: `/v1/messages`, `x-api-key`, SSE event stream. */
    ANTHROPIC,

    /** Gemini: `/v1beta/models/{model}:generateContent`, `x-goog-api-key`. */
    GEMINI;

    companion object {
        /** Stored-value mapping; anything unrecognised falls back to [OPENAI]. */
        fun fromStored(value: String?): AiProtocol =
            entries.firstOrNull { it.name == value?.trim()?.uppercase() } ?: OPENAI
    }
}