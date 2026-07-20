package dev.koda.core.adapter

import dev.koda.core.PermissionMode
import dev.koda.core.port.PermissionPolicy
import dev.koda.tools.KodaTool

/** v0 policy: approval by [PermissionMode] plus session-scoped "always allow" grants. */
class ModePermissionPolicy(initialMode: PermissionMode) : PermissionPolicy {
    @Volatile
    private var mode: PermissionMode = initialMode
    private val alwaysAllowed = mutableSetOf<String>()

    override fun needsApproval(tool: KodaTool): Boolean {
        if (!tool.mutating) return false
        if (tool.name in alwaysAllowed) return false
        return when (mode) {
            PermissionMode.YOLO -> false
            PermissionMode.ACCEPT_EDITS -> tool.name == "bash"
            PermissionMode.DEFAULT -> true
        }
    }

    override fun allowAlways(toolName: String) {
        alwaysAllowed += toolName
    }

    override fun updateMode(mode: PermissionMode) {
        this.mode = mode
    }
}
