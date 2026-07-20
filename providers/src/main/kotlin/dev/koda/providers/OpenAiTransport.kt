package dev.koda.providers

import io.ktor.client.request.headers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions adapter. Covers OpenAI itself plus every
 * OpenAI-compatible host (OpenRouter, DeepSeek, Ollama, xAI, local
 * endpoints, ...) by swapping [ProviderConfig.baseUrl].
 */
class OpenAiTransport(private val config: ProviderConfig) : ProviderTransport {
    override val providerName: String get() = config.name

    private val http = defaultHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(
        request: LlmRequest,
        onDelta: suspend (StreamDelta) -> Unit,
    ): LlmResponse {
        val body = buildRequestBody(request)

        val textParts = StringBuilder()
        val reasoningParts = StringBuilder()
        // tool_calls delta index -> in-progress call
        val toolCalls = LinkedHashMap<Int, PendingToolCall>()
        var usage = Usage()
        var stopReason = StopReason.OTHER

        http.postSse(
            url = "${config.baseUrl.trimEnd('/')}/chat/completions",
            body = body.toString(),
            configure = {
                headers { append("Authorization", "Bearer ${config.apiKey}") }
            },
        ) { payload ->
            val chunk = json.parseToJsonElement(payload).jsonObject

            chunk["usage"]?.takeIf { it !is JsonNull }?.jsonObject?.let { u ->
                usage = Usage(
                    inputTokens = u["prompt_tokens"]?.jsonPrimitive?.long ?: 0,
                    outputTokens = u["completion_tokens"]?.jsonPrimitive?.long ?: 0,
                    cacheReadTokens = u["prompt_tokens_details"]?.jsonObject
                        ?.get("cached_tokens")?.jsonPrimitive?.long ?: 0,
                )
            }

            val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@postSse

            when (choice["finish_reason"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content) {
                "stop" -> stopReason = StopReason.END_TURN
                "tool_calls" -> stopReason = StopReason.TOOL_USE
                "length" -> stopReason = StopReason.MAX_TOKENS
            }

            val delta = choice["delta"]?.jsonObject ?: return@postSse

            delta["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { text ->
                if (text.isNotEmpty()) {
                    textParts.append(text)
                    onDelta(StreamDelta.Text(text))
                }
            }
            // Non-standard but widely used by reasoning-capable OpenAI-compatible hosts.
            delta["reasoning_content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { text ->
                if (text.isNotEmpty()) {
                    reasoningParts.append(text)
                    onDelta(StreamDelta.Reasoning(text))
                }
            }

            delta["tool_calls"]?.jsonArray?.forEach { element ->
                val callDelta = element.jsonObject
                val index = callDelta["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                val pending = toolCalls.getOrPut(index) { PendingToolCall() }
                callDelta["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { pending.id = it }
                callDelta["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { name ->
                        if (pending.name.isEmpty()) {
                            pending.name = name
                            onDelta(StreamDelta.ToolCallStarted(name))
                        }
                    }
                    fn["arguments"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
                        pending.arguments.append(it)
                    }
                }
            }
        }

        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = textParts.toString().ifEmpty { null },
            reasoning = reasoningParts.toString().ifEmpty { null },
            toolCalls = toolCalls.values
                .filter { it.name.isNotEmpty() }
                .map { ToolCallRequest(it.id, it.name, it.arguments.toString().ifEmpty { "{}" }) },
        )
        return LlmResponse(message, usage, stopReason)
    }

    private fun buildRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("max_tokens", request.maxTokens)
        request.temperature?.let { put("temperature", it) }
        putJsonObject("stream_options") { put("include_usage", true) }

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                        }
                    })
                }
            }
        }

        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.system)
            })
            request.messages.forEach { msg ->
                add(convertMessage(msg))
            }
        }
    }

    private fun convertMessage(msg: ChatMessage): JsonObject = buildJsonObject {
        when (msg.role) {
            Role.USER -> {
                put("role", "user")
                put("content", msg.content ?: "")
            }
            Role.ASSISTANT -> {
                put("role", "assistant")
                if (msg.content != null) put("content", msg.content) else put("content", JsonNull)
                if (msg.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        msg.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                }
                            })
                        }
                    }
                }
            }
            Role.TOOL -> {
                put("role", "tool")
                put("tool_call_id", msg.toolCallId ?: "")
                put("content", msg.content ?: "")
            }
            Role.SYSTEM -> {
                put("role", "system")
                put("content", msg.content ?: "")
            }
        }
    }

    override fun close() = http.close()

    private class PendingToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
