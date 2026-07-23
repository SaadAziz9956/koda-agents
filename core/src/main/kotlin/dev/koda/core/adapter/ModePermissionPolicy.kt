package dev.koda.core.adapter

import dev.koda.core.PermissionMode
import dev.koda.core.port.PermissionPolicy

/** v0 policy: approval by [PermissionMode] plus session-scoped "always allow" grants. */
class ModePermissionPolicy(initialMode: PermissionMode) : PermissionPolicy {
    @Volatile
    private var mode: PermissionMode = initialMode
    private val alwaysAllowed = mutableSetOf<String>()

    override fun needsApproval(toolName: String, mutating: Boolean): Boolean {
        if (!mutating) return false
        if (toolName in alwaysAllowed) return false
        return when (mode) {
            PermissionMode.YOLO -> false
            PermissionMode.ACCEPT_EDITS -> toolName == "bash"
            PermissionMode.DEFAULT -> true
            PermissionMode.PLAN -> true // moot: the gate blocks mutations first
        }
    }

    override fun allowAlways(toolName: String) {
        alwaysAllowed += toolName
    }

    override fun updateMode(mode: PermissionMode) {
        this.mode = mode
    }

    override fun blocksMutations(): Boolean = mode == PermissionMode.PLAN
}
