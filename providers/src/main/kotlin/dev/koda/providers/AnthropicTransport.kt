package dev.koda.providers

import io.ktor.client.request.headers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Anthropic Messages API adapter (api.anthropic.com/v1/messages).
 *
 * Cache discipline: the system prompt carries a cache_control breakpoint and
 * the conversation is sent append-only, so the prefix stays byte-stable and
 * cacheable across iterations. Consecutive TOOL messages are folded into a
 * single user message of tool_result blocks to preserve role alternation.
 */
class AnthropicTransport(private val config: ProviderConfig) : ProviderTransport {
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
        // content block index -> in-progress tool call
        val toolBlocks = LinkedHashMap<Int, PendingToolCall>()
        var usage = Usage()
        var stopReason = StopReason.OTHER

        http.postSse(
            url = "${config.baseUrl.trimEnd('/')}/v1/messages",
            body = body.toString(),
            configure = {
                headers {
                    append("x-api-key", config.apiKey)
                    append("anthropic-version", "2023-06-01")
                }
            },
        ) { payload ->
            val event = json.parseToJsonElement(payload).jsonObject
            when (event["type"]?.jsonPrimitive?.content) {
                "message_start" -> {
                    val u = event["message"]?.jsonObject?.get("usage")?.jsonObject
                    usage = usage + Usage(
                        inputTokens = u?.get("input_tokens")?.jsonPrimitive?.long ?: 0,
                        cacheReadTokens = u?.get("cache_read_input_tokens")?.jsonPrimitive?.long ?: 0,
                        cacheWriteTokens = u?.get("cache_creation_input_tokens")?.jsonPrimitive?.long ?: 0,
                    )
                }

                "content_block_start" -> {
                    val index = event["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                    val block = event["content_block"]?.jsonObject ?: return@postSse
                    if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                        val name = block["name"]?.jsonPrimitive?.content ?: ""
                        toolBlocks[index] = PendingToolCall(
                            id = block["id"]?.jsonPrimitive?.content ?: "",
                            name = name,
                        )
                        onDelta(StreamDelta.ToolCallStarted(name))
                    }
                }

                "content_block_delta" -> {
                    val index = event["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                    val delta = event["delta"]?.jsonObject ?: return@postSse
                    when (delta["type"]?.jsonPrimitive?.content) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.content ?: ""
                            textParts.append(text)
                            onDelta(StreamDelta.Text(text))
                        }
                        "thinking_delta" -> {
                            val text = delta["thinking"]?.jsonPrimitive?.content ?: ""
                            reasoningParts.append(text)
                            onDelta(StreamDelta.Reasoning(text))
                        }
                        "input_json_delta" -> {
                            toolBlocks[index]?.arguments?.append(
                                delta["partial_json"]?.jsonPrimitive?.content ?: ""
                            )
                        }
                    }
                }

                "message_delta" -> {
                    val delta = event["delta"]?.jsonObject
                    when (delta?.get("stop_reason")?.jsonPrimitive?.content) {
                        "end_turn", "stop_sequence" -> stopReason = StopReason.END_TURN
                        "tool_use" -> stopReason = StopReason.TOOL_USE
                        "max_tokens" -> stopReason = StopReason.MAX_TOKENS
                    }
                    val u = event["usage"]?.jsonObject
                    usage = usage + Usage(
                        outputTokens = u?.get("output_tokens")?.jsonPrimitive?.long ?: 0,
                    )
                }
            }
        }

        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = textParts.toString().ifEmpty { null },
            reasoning = reasoningParts.toString().ifEmpty { null },
            toolCalls = toolBlocks.values.map {
                ToolCallRequest(it.id, it.name, it.arguments.toString().ifEmpty { "{}" })
            },
        )
        return LlmResponse(message, usage, stopReason)
    }

    private fun buildRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        request.temperature?.let { put("temperature", it) }

        putJsonArray("system") {
            add(buildJsonObject {
                put("type", "text")
                put("text", request.system)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            })
        }

        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters)
                    })
                }
            }
        }

        putJsonArray("messages") {
            convertMessages(request.messages).forEach { add(it) }
        }
    }

    /** Converts normalized messages to Anthropic's alternating user/assistant shape. */
    private fun convertMessages(messages: List<ChatMessage>): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                Role.USER -> result += buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content ?: "")
                        })
                    }
                }

                Role.ASSISTANT -> result += buildJsonObject {
                    put("role", "assistant")
                    putJsonArray("content") {
                        if (!msg.content.isNullOrEmpty()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            })
                        }
                        msg.toolCalls.forEach { call ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", parseArgsOrEmpty(call.arguments))
                            })
                        }
                    }
                }

                Role.TOOL -> {
                    // Fold this and all consecutive TOOL messages into one user message.
                    val results = buildJsonArray {
                        while (i < messages.size && messages[i].role == Role.TOOL) {
                            val toolMsg = messages[i]
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", toolMsg.toolCallId ?: "")
                                put("content", toolMsg.content ?: "")
                                if (toolMsg.isError) put("is_error", true)
                            })
                            i++
                        }
                        i-- // outer loop increments past the last folded message
                    }
                    result += buildJsonObject {
                        put("role", "user")
                        put("content", results)
                    }
                }

                Role.SYSTEM -> {} // carried separately in the request body
            }
            i++
        }
        return result
    }

    private fun parseArgsOrEmpty(arguments: String): JsonObject = try {
        json.parseToJsonElement(arguments).jsonObject
    } catch (_: Exception) {
        buildJsonObject {}
    }

    override fun close() = http.close()

    private class PendingToolCall(val id: String, val name: String) {
        val arguments = StringBuilder()
    }
}
