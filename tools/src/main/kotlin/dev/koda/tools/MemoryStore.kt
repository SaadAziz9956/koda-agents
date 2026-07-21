package dev.koda.tools

/**
 * Persistent memory the agent maintains across sessions. USER scope holds
 * durable facts about the user (preferences, how they work); PROJECT scope
 * holds facts about the current codebase. Where it's stored is an adapter
 * concern; the tool only appends/replaces/reads text.
 */
enum class MemoryScope { USER, PROJECT }

interface MemoryStore {
    fun load(scope: MemoryScope): String
    fun append(scope: MemoryScope, entry: String)
    fun replace(scope: MemoryScope, content: String)
}

/** No-op store for contexts without persistence (e.g. the standalone tool registry). */
class NoopMemoryStore : MemoryStore {
    override fun load(scope: MemoryScope) = ""
    override fun append(scope: MemoryScope, entry: String) {}
    override fun replace(scope: MemoryScope, content: String) {}
}
