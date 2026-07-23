package dev.koda.core.engine

import dev.koda.core.AgentSession
import dev.koda.core.ContextFiles
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.Event
import dev.koda.protocol.Notice
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.tools.KodaTool
import dev.koda.tools.ToolResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Emits protocol events to whoever is listening; the engine never knows who. */
fun interface EventSink {
    suspend fun emit(event: Event)
}

/**
 * The permission gate in front of every tool dispatch — built-in and
 * external (MCP) alike. Fires PreToolUse hooks (which may deny, auto-allow,
 * or rewrite args), runs the approval handshake, emits ToolBegin/ToolEnd,
 * executes, then fires PostToolUse hooks.
 */
class ToolGate(
    private val session: AgentSession,
    private val events: EventSink,
    private val hooks: HookRunner,
) {
    private val cwd get() = session.toolContext.cwd

    /** Dispatch a built-in Koda domain tool. Supports hook arg-rewrite. */
    suspend fun run(tool: KodaTool, args: JsonObject): String =
        gated(tool.name, tool.mutating, tool.summarize(args), args, canRewrite = true) { finalArgs ->
            try {
                tool.execute(finalArgs, session.toolContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.error("Tool ${tool.name} failed: ${e.message ?: e.toString()}")
            }
        }

    /**
     * Dispatch an external (MCP) tool. External tools are conservatively
     * treated as mutating; hook arg-rewrite is not supported here (the call
     * is already bound), only allow/deny.
     */
    suspend fun runExternal(toolName: String, argsText: String, execute: suspend () -> String): String {
        val argsJson = runCatching { Json.parseToJsonElement(argsText).jsonObject }.getOrDefault(buildJsonObject {})
        return gated(toolName, mutating = true, "$toolName ${argsText.take(120)}", argsJson, canRewrite = false) {
            try {
                ToolResult(execute())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.error("Tool $toolName failed: ${e.message ?: e.toString()}")
            }
        }
    }

    private suspend fun gated(
        toolName: String,
        mutating: Boolean,
        summary: String,
        args: JsonObject,
        canRewrite: Boolean,
        execute: suspend (JsonObject) -> ToolResult,
    ): String {
        var effectiveArgs = args
        var autoAllow = false
        when (val decision = hooks.firePreToolUse(session.id, cwd, toolName, args)) {
            is PreToolDecision.Deny ->
                return "DENIED by hook: ${decision.reason}. Do not retry without addressing it."
            is PreToolDecision.Rewrite ->
                if (canRewrite) effectiveArgs = decision.args
                else events.emit(Notice(session.id, "hook arg-rewrite ignored for external tool '$toolName'"))
            PreToolDecision.Allow -> autoAllow = true
            PreToolDecision.Proceed -> {}
        }

        // A dangerous shell command forces approval regardless of mode (guardrail).
        val danger = if (toolName == "bash") {
            DangerousCommands.match(effectiveArgs["command"]?.jsonPrimitive?.contentOrNull ?: "")
        } else null

        if (danger != null || (!autoAllow && session.permissions.needsApproval(toolName, mutating))) {
            val approvalId = UUID.randomUUID().toString()
            val shownSummary = if (danger != null) "⚠ DANGEROUS ($danger): $summary" else summary
            events.emit(ApprovalRequest(session.id, approvalId, toolName, shownSummary, effectiveArgs.toString()))
            when (session.approvals.await(approvalId)) {
                ApprovalDecision.DENY ->
                    return "DENIED: the user denied this tool call. Ask before retrying it."
                ApprovalDecision.APPROVE_ALWAYS -> session.permissions.allowAlways(toolName)
                ApprovalDecision.APPROVE -> {}
            }
        }

        val callId = UUID.randomUUID().toString()
        events.emit(ToolBegin(session.id, session.currentTurnId, callId, toolName, effectiveArgs.toString()))
        val result = execute(effectiveArgs)
        events.emit(ToolEnd(session.id, session.currentTurnId, callId, toolName, result.output, result.isError, result.diff))
        hooks.firePostToolUse(session.id, cwd, toolName, effectiveArgs, result.output, result.isError)
        if (result.isError) return "ERROR: ${result.output}"
        return result.output + subtreeContext(toolName, effectiveArgs)
    }

    /**
     * When the agent reads a file in a subdirectory below cwd, surface that
     * subtree's context files (once) by appending them to the tool result —
     * NOT the frozen system prompt, so the prompt-cache prefix is unaffected.
     */
    private fun subtreeContext(toolName: String, args: JsonObject): String {
        if (toolName != "read") return ""
        val path = args["file_path"]?.jsonPrimitive?.contentOrNull ?: return ""
        val touched = cwd.resolve(path)
        val layers = ContextFiles.subtreeLayers(cwd, touched, session.toolContext.seenContextDirs)
        if (layers.isEmpty()) return ""
        return buildString {
            append("\n\n--- context files for this subtree (apply while working here) ---")
            layers.forEach { append("\n\n## ${it.label}\n${it.content}") }
        }
    }
}
