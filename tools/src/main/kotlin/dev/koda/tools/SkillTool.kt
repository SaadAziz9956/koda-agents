package dev.koda.tools

import kotlinx.serialization.json.JsonObject

/**
 * A skill loaded from a SKILL.md file (agent-skills open standard).
 * Only [name] and [description] enter the system prompt; the [body] is
 * loaded on demand through [SkillTool] — progressive disclosure keeps
 * the prompt small and cache-stable.
 */
data class LoadedSkill(
    val name: String,
    val description: String,
    val body: String,
    /** Directory containing SKILL.md — scripts/references live beside it. */
    val dir: String,
)

class SkillTool : KodaTool {
    override val name = "skill"
    override val description =
        "Load the full instructions of an available skill by name. Skills are " +
        "listed in the system prompt; invoke this before following one."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("name"),
        properties = mapOf(
            "name" to ("string" to "Name of the skill to load"),
        ),
    )

    override fun summarize(args: JsonObject) = "skill ${args.requiredString("name")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val name = args.requiredString("name")
        val skill = ctx.skills[name]
            ?: return ToolResult.error(
                "Unknown skill: $name. Available: ${ctx.skills.keys.sorted().joinToString(", ").ifEmpty { "(none)" }}"
            )
        return ToolResult(
            buildString {
                appendLine("# Skill: ${skill.name}")
                appendLine("(files for this skill live in: ${skill.dir})")
                appendLine()
                append(skill.body)
            }
        )
    }
}
