package dev.koda.tools

import kotlinx.serialization.json.JsonObject

/**
 * Lets the agent turn a reusable procedure it worked out into a saved skill
 * (SKILL.md), available in future sessions. This is the "gets better with
 * experience" half of the learning loop, alongside memory.
 */
class SkillCreateTool(private val writer: SkillWriter) : KodaTool {
    override val name = "skill_create"
    override val description =
        "Save a reusable procedure as a skill for future sessions. Use when you've worked out " +
        "a repeatable how-to worth keeping (name: short kebab-case; description: one line on when " +
        "to use it; body: the markdown instructions). Create skills sparingly — only genuinely " +
        "reusable procedures, not one-off answers."
    override val mutating = true
    override val parameters = objectSchema(
        required = listOf("name", "description", "body"),
        properties = mapOf(
            "name" to ("string" to "Short kebab-case skill name, e.g. deploy-staging"),
            "description" to ("string" to "One line: what the skill does and when to use it"),
            "body" to ("string" to "Markdown instructions for the procedure"),
        ),
    )

    override fun summarize(args: JsonObject) = "create skill '${args.requiredString("name")}'"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.requiredString("name").trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-')
        if (name.isEmpty()) return ToolResult.error("Invalid skill name.")
        val description = args.requiredString("description").trim()
        val body = args.requiredString("body").trim()
        return ToolResult(writer.create(name, description, body))
    }
}
