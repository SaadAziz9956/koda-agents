package dev.koda.core

import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Event
import dev.koda.protocol.ReasoningDelta
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStarted
import dev.koda.protocol.TurnStopReason
import dev.koda.providers.ChatMessage
import dev.koda.providers.LlmRequest
import dev.koda.providers.ProviderTransport
import dev.koda.providers.Role
import dev.koda.providers.StreamDelta
import dev.koda.providers.ToolCallRequest
import dev.koda.providers.ToolSpec
import dev.koda.tools.ToolRegistry
import dev.koda.tools.ToolResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Emits protocol events to whoever is listening; the loop never knows who. */
fun interface EventSink {
    suspend fun emit(event: Event)
}

/**
 * The agent loop use case: one user turn from submission to completion.
 * model call -> stream deltas -> permission-gated tool dispatch -> repeat
 * until a text-only response, the iteration budget, or an interrupt.
 *
 * Depends only on abstractions ([ProviderTransport], [ToolRegistry],
 * [EventSink], and the ports carried by [AgentSession]).
 */
class AgentLoop(
    private val transport: ProviderTransport,
    private val registry: ToolRegistry,
    private val events: EventSink,
    private val model: String,
    private val maxTokens: Int,
    private val maxIterationsPerTurn: Int,
) {
    private val toolSpecs: List<ToolSpec> =
        registry.all.map { ToolSpec(it.name, it.description, it.parameters) }

    suspend fun runTurn(session: AgentSession, text: String) {
        val turnId = UUID.randomUUID().toString()
        events.emit(TurnStarted(session.id, turnId))

        var stopReason = TurnStopReason.COMPLETED
        try {
            session.addMessage(ChatMessage(Role.USER, text))
            var iterations = 0
            while (true) {
                if (++iterations > maxIterationsPerTurn) {
                    stopReason = TurnStopReason.MAX_ITERATIONS
                    break
                }
                val hadToolCalls = runIteration(session, turnId)
                if (!hadToolCalls) break
            }
        } catch (e: CancellationException) {
            stopReason = TurnStopReason.INTERRUPTED
            withContext(NonCancellable) { repairDanglingToolCalls(session) }
        } catch (e: Exception) {
            stopReason = TurnStopReason.ERROR
            events.emit(ErrorEvent(session.id, e.message ?: e.toString()))
        }

        withContext(NonCancellable) {
            events.emit(TurnCompleted(session.id, turnId, stopReason))
        }
    }

    /** One model call plus its tool dispatches. Returns true if tools ran. */
    private suspend fun runIteration(session: AgentSession, turnId: String): Boolean {
        val response = transport.complete(
            LlmRequest(
                model = model,
                system = session.systemPrompt,
                messages = session.messages,
                tools = toolSpecs,
                maxTokens = maxTokens,
            )
        ) { delta ->
            when (delta) {
                is StreamDelta.Text -> events.emit(TextDelta(session.id, turnId, delta.text))
                is StreamDelta.Reasoning -> events.emit(ReasoningDelta(session.id, turnId, delta.text))
                is StreamDelta.ToolCallStarted -> {}
            }
        }

        session.addMessage(response.message)
        events.emit(
            TokenUsage(
                session.id,
                response.usage.inputTokens,
                response.usage.outputTokens,
                response.usage.cacheReadTokens,
                response.usage.cacheWriteTokens,
            )
        )
        response.message.content?.let {
            events.emit(AssistantMessage(session.id, turnId, it))
        }

        if (response.message.toolCalls.isEmpty()) return false

        for (call in response.message.toolCalls) {
            val result = executeCall(session, turnId, call)
            session.addMessage(
                ChatMessage(
                    role = Role.TOOL,
                    content = result.output,
                    toolCallId = call.id,
                    isError = result.isError,
                )
            )
        }
        return true
    }

    private suspend fun executeCall(
        session: AgentSession,
        turnId: String,
        call: ToolCallRequest,
    ): ToolResult {
        val tool = registry[call.name]
            ?: return ToolResult.error("Unknown tool: ${call.name}")

        val args = try {
            Json.parseToJsonElement(call.arguments).jsonObject
        } catch (_: Exception) {
            buildJsonObject {}
        }

        if (session.permissions.needsApproval(tool)) {
            val approvalId = UUID.randomUUID().toString()
            events.emit(
                ApprovalRequest(session.id, approvalId, tool.name, tool.summarize(args), call.arguments)
            )
            when (session.approvals.await(approvalId)) {
                ApprovalDecision.DENY ->
                    return ToolResult.error("User denied this tool call. Ask before retrying it.")
                ApprovalDecision.APPROVE_ALWAYS -> session.permissions.allowAlways(tool.name)
                ApprovalDecision.APPROVE -> {}
            }
        }

        events.emit(ToolBegin(session.id, turnId, call.id, tool.name, call.arguments))
        val result = try {
            tool.execute(args, session.toolContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Tool ${tool.name} failed: ${e.message ?: e.toString()}")
        }
        events.emit(ToolEnd(session.id, turnId, call.id, tool.name, result.output, result.isError))
        return result
    }

    /**
     * After an interrupt, the last assistant message may contain tool calls
     * with no results — an invalid conversation for every provider. Backfill
     * synthetic interrupted results so the session stays resumable.
     */
    private fun repairDanglingToolCalls(session: AgentSession) {
        val last = session.messages.lastOrNull() ?: return
        if (last.role != Role.ASSISTANT || last.toolCalls.isEmpty()) return
        val answered = session.messages.mapNotNull { it.toolCallId }.toSet()
        last.toolCalls
            .filter { it.id !in answered }
            .forEach { call ->
                session.addMessage(
                    ChatMessage(
                        role = Role.TOOL,
                        content = "Interrupted by user before this tool call ran.",
                        toolCallId = call.id,
                        isError = true,
                    )
                )
            }
    }
}
