package dev.koda.core

import java.time.LocalDate

object SystemPrompt {

    private const val MAX_SKILL_LINES = 150

    fun build(config: KodaConfig, skills: Map<String, dev.koda.tools.LoadedSkill> = emptyMap()): String = buildString {
        appendLine(
            """
            You are Koda, an autonomous personal agent running on the user's machine.
            You help with software engineering and general tasks by using the tools
            available to you. Be direct and concise. When a task needs multiple steps,
            use the todo tool to plan and track them.

            Rules:
            - Read files before editing or overwriting them.
            - Prefer the edit tool (exact string replacement) over rewriting whole files.
            - After making changes, verify them (run builds, tests, or the code itself).
            - Report outcomes faithfully; if something failed, say so with the evidence.
            """.trimIndent()
        )

        appendLine()
        appendLine("# Environment")
        appendLine("Working directory: ${config.cwd}")
        appendLine("Platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        appendLine("Date: ${LocalDate.now()}")
        appendLine("Model: ${config.model} (${config.provider.name})")

        val contextLayers = ContextFiles.load(config.kodaHome, config.cwd)
        if (contextLayers.isNotEmpty()) {
            appendLine()
            appendLine("# Project context")
            appendLine(
                "Instructions from context files, broadest first; where they conflict, " +
                    "the most specific (listed last) takes precedence."
            )
            contextLayers.forEach { layer ->
                appendLine()
                appendLine("## ${layer.label}")
                appendLine(layer.content)
            }
        }

        if (skills.isNotEmpty()) {
            appendLine()
            appendLine("# Skills")
            appendLine(
                "Specialized instructions you can load on demand. When a task matches a " +
                    "skill, call the skill tool with its name FIRST and follow the loaded instructions."
            )
            skills.values.sortedBy { it.name }.take(MAX_SKILL_LINES).forEach { skill ->
                appendLine("- ${skill.name}: ${skill.description}")
            }
            if (skills.size > MAX_SKILL_LINES) {
                appendLine("… and ${skills.size - MAX_SKILL_LINES} more (any can be loaded by name)")
            }
        }
    }
}
