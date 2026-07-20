package dev.koda.core.port

import dev.koda.core.PermissionMode

/**
 * Port deciding whether a tool call needs user approval. Keyed by tool name
 * and mutation flag so built-in and external (MCP) tools gate identically.
 * One instance per session; grants are session-scoped.
 */
interface PermissionPolicy {
    fun needsApproval(toolName: String, mutating: Boolean): Boolean
    fun allowAlways(toolName: String)

    /** The user changed the session's permission mode at runtime. */
    fun updateMode(mode: PermissionMode)
}
