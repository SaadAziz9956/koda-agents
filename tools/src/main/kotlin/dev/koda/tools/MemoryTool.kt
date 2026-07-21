package dev.koda.tools

import kotlinx.serialization.json.JsonObject

/**
 * Lets the agent persist durable knowledge across sessions. Saved memory is
 * injected into the system prompt at the start of future sessions. Writes
 * take effect next session (the current prompt is frozen for cache stability),
 * so this is for "remember this for next time", not within-turn state.
 */
class MemoryTool(private val store: MemoryStore) : KodaTool {
    override val name = "memory"
    override val description =
        "Save a durable fact to remember in future sessions. Use scope 'user' for lasting " +
        "facts about the user (preferences, working style) and 'project' for facts about this " +
        "codebase. action 'add' appends one entry (default); 'replace' overwrites the whole " +
        "scope to consolidate. Save sparingly — only genuinely durable, reusable facts."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("content"),
        properties = mapOf(
            "content" to ("string" to "The fact to remember (one concise entry, or full text for replace)"),
            "scope" to ("string" to "'user' or 'project' (default 'project')"),
            "action" to ("string" to "'add' (default) or 'replace'"),
        ),
    )

    override fun summarize(args: JsonObject): String {
        val scope = args.optionalString("scope") ?: "project"
        return "remember ($scope): ${args.requiredString("content").take(80)}"
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val content = args.requiredString("content").trim()
        if (content.isEmpty()) return ToolResult.error("Nothing to remember (empty content).")
        val scope = when (args.optionalString("scope")?.lowercase()) {
            "user" -> MemoryScope.USER
            else -> MemoryScope.PROJECT
        }
        return when (args.optionalString("action")?.lowercase()) {
            "replace" -> {
                store.replace(scope, content)
                ToolResult("Replaced ${scope.name.lowercase()} memory.")
            }
            else -> {
                store.append(scope, content)
                ToolResult("Saved to ${scope.name.lowercase()} memory (applies next session).")
            }
        }
    }
}
