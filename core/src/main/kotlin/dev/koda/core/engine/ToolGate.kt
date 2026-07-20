package dev.koda.core.engine

import dev.koda.core.AgentSession
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.Event
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.tools.KodaTool
import dev.koda.tools.ToolResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Emits protocol events to whoever is listening; the engine never knows who. */
fun interface EventSink {
    suspend fun emit(event: Event)
}

/**
 * The permission gate in front of every tool dispatch — built-in and
 * external (MCP) alike. Koog invokes the bridged tools; the bridged tools
 * invoke this; this consults the session's permission policy, runs the
 * approval handshake when needed, emits ToolBegin/ToolEnd, and only then
 * executes.
 */
class ToolGate(
    private val session: AgentSession,
    private val events: EventSink,
) {
    /** Dispatch a built-in Koda domain tool. */
    suspend fun run(tool: KodaTool, args: JsonObject): String =
        gated(tool.name, mutating = tool.mutating, summary = tool.summarize(args), argsText = args.toString()) {
            val result = try {
                tool.execute(args, session.toolContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.error("Tool ${tool.name} failed: ${e.message ?: e.toString()}")
            }
            result
        }

    /**
     * Dispatch an external (MCP) tool. External tools are conservatively
     * treated as mutating — a remote server can do anything.
     */
    suspend fun runExternal(toolName: String, argsText: String, execute: suspend () -> String): String =
        gated(toolName, mutating = true, summary = "$toolName ${argsText.take(120)}", argsText = argsText) {
            try {
                ToolResult(execute())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.error("Tool $toolName failed: ${e.message ?: e.toString()}")
            }
        }

    private suspend fun gated(
        toolName: String,
        mutating: Boolean,
        summary: String,
        argsText: String,
        execute: suspend () -> ToolResult,
    ): String {
        if (session.permissions.needsApproval(toolName, mutating)) {
            val approvalId = UUID.randomUUID().toString()
            events.emit(ApprovalRequest(session.id, approvalId, toolName, summary, argsText))
            when (session.approvals.await(approvalId)) {
                ApprovalDecision.DENY ->
                    return "DENIED: the user denied this tool call. Ask before retrying it."
                ApprovalDecision.APPROVE_ALWAYS -> session.permissions.allowAlways(toolName)
                ApprovalDecision.APPROVE -> {}
            }
        }

        val callId = UUID.randomUUID().toString()
        events.emit(ToolBegin(session.id, session.currentTurnId, callId, toolName, argsText))
        val result = execute()
        events.emit(
            ToolEnd(session.id, session.currentTurnId, callId, toolName, result.output, result.isError)
        )
        return if (result.isError) "ERROR: ${result.output}" else result.output
    }
}
