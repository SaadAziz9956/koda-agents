package dev.koda.core

import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Assembles the session system prompt. Called exactly once per session:
 * the result must stay byte-stable for the session's lifetime so the
 * provider prompt cache holds (Koda design law #1).
 */
object SystemPrompt {

    private const val MAX_CONTEXT_FILE_CHARS = 20_000
    private val CONTEXT_FILE_NAMES = listOf("KODA.md", "AGENTS.md", "CLAUDE.md")

    fun build(config: KodaConfig): String = buildString {
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

        projectContext(config.cwd)?.let { (name, content) ->
            appendLine()
            appendLine("# Project context ($name)")
            appendLine(content)
        }
    }

    /** First matching context file walking from cwd up to the git root (or filesystem root). */
    private fun projectContext(cwd: Path): Pair<String, String>? {
        var dir: Path? = cwd
        while (dir != null) {
            for (name in CONTEXT_FILE_NAMES) {
                val file = dir.resolve(name)
                if (file.exists()) {
                    val text = file.readText().take(MAX_CONTEXT_FILE_CHARS)
                    return name to text
                }
            }
            // Stop at the repository boundary.
            if (dir.resolve(".git").exists()) break
            dir = dir.parent
        }
        return null
    }
}
