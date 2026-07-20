package dev.koda.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Koda's normalized chat model. Every provider transport converts to/from
 * this shape; nothing outside the providers module speaks a vendor API.
 */

@Serializable
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class ToolCallRequest(
    val id: String,
    val name: String,
    /** Raw JSON string of the tool arguments as emitted by the model. */
    val arguments: String,
)

@Serializable
data class ChatMessage(
    val role: Role,
    val content: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    /** For [Role.TOOL] messages: the id of the call this result answers. */
    val toolCallId: String? = null,
    /** For [Role.TOOL] messages: whether the tool errored. */
    val isError: Boolean = false,
    /** Reasoning/thinking content, kept out of [content]. */
    val reasoning: String? = null,
)

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments object. */
    val parameters: JsonObject,
)

data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) {
    operator fun plus(other: Usage) = Usage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens + other.cacheWriteTokens,
    )
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, OTHER }

data class LlmRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 8192,
    val temperature: Double? = null,
)

data class LlmResponse(
    val message: ChatMessage,
    val usage: Usage,
    val stopReason: StopReason,
)

/** Streaming deltas surfaced while a completion is in flight. */
sealed interface StreamDelta {
    data class Text(val text: String) : StreamDelta
    data class Reasoning(val text: String) : StreamDelta
    /** A tool call started streaming (name known before arguments finish). */
    data class ToolCallStarted(val name: String) : StreamDelta
}

class ProviderException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)
