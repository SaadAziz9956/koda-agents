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
 * The permission gate in front of every tool dispatch. Koog invokes the
 * bridged tools; the bridged tools invoke this; this consults the session's
 * [dev.koda.core.port.PermissionPolicy], runs the approval handshake when
 * needed, emits ToolBegin/ToolEnd, and only then runs the domain tool.
 */
class ToolGate(
    private val session: AgentSession,
    private val events: EventSink,
) {
    suspend fun run(tool: KodaTool, args: JsonObject): String {
        if (session.permissions.needsApproval(tool)) {
            val approvalId = UUID.randomUUID().toString()
            events.emit(
                ApprovalRequest(session.id, approvalId, tool.name, tool.summarize(args), args.toString())
            )
            when (session.approvals.await(approvalId)) {
                ApprovalDecision.DENY ->
                    return "DENIED: the user denied this tool call. Ask before retrying it."
                ApprovalDecision.APPROVE_ALWAYS -> session.permissions.allowAlways(tool.name)
                ApprovalDecision.APPROVE -> {}
            }
        }

        val callId = UUID.randomUUID().toString()
        events.emit(ToolBegin(session.id, session.currentTurnId, callId, tool.name, args.toString()))
        val result = try {
            tool.execute(args, session.toolContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Tool ${tool.name} failed: ${e.message ?: e.toString()}")
        }
        events.emit(
            ToolEnd(session.id, session.currentTurnId, callId, tool.name, result.output, result.isError)
        )
        return if (result.isError) "ERROR: ${result.output}" else result.output
    }
}
