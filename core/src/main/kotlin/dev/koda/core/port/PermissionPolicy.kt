package dev.koda.core.port

import dev.koda.tools.KodaTool

/**
 * Port deciding whether a tool call needs user approval. Implementations
 * range from mode-based (v0) to rule engines with glob matching (later).
 * One instance per session; grants are session-scoped.
 */
interface PermissionPolicy {
    fun needsApproval(tool: KodaTool): Boolean
    fun allowAlways(toolName: String)
}
