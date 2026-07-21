package dev.koda.core

import dev.koda.tools.LoadedSkill
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.asSequence

/**
 * Discovers SKILL.md files (agent-skills open standard) under
 * `<kodaHome>/skills` and `<cwd>/.koda/skills`, recursively — skills may be
 * grouped in category directories. Project skills win on name clashes.
 * Only name + description are surfaced to the prompt; bodies load on demand.
 */
object SkillsLoader {

    private const val MAX_SKILL_DEPTH = 4

    fun load(kodaHome: Path, cwd: Path): Map<String, LoadedSkill> {
        val global = scan(kodaHome.resolve("skills"))
        val project = scan(cwd.resolve(".koda").resolve("skills"))
        return (global + project).associateBy { it.name }
    }

    private fun scan(root: Path): List<LoadedSkill> {
        if (!root.exists()) return emptyList()
        return Files.walk(root, MAX_SKILL_DEPTH).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.name == "SKILL.md" }
                .mapNotNull { parse(it) }
                .toList()
        }
    }

    private fun parse(file: Path): LoadedSkill? = runCatching {
        val text = file.readText()
        val (frontmatter, body) = splitFrontmatter(text) ?: return null
        val name = frontmatterValue(frontmatter, "name")
            ?: file.parent.name
        val description = frontmatterValue(frontmatter, "description") ?: ""
        LoadedSkill(
            name = name,
            description = Sanitizer.clean(description),
            // Skills are third-party (177 imported) — sanitize and flag injection.
            body = Sanitizer.sanitizeUntrusted(body.trim(), "skill"),
            dir = file.parent.toString(),
        )
    }.getOrNull()

    private fun splitFrontmatter(text: String): Pair<String, String>? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return null
        val frontmatter = text.substring(3, end)
        val body = text.substring(end + 4).removePrefix("\n")
        return frontmatter to body
    }

    /** Minimal YAML scalar extraction: `key: value`, optionally quoted. */
    private fun frontmatterValue(frontmatter: String, key: String): String? =
        frontmatter.lineSequence()
            .firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter("$key:")
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.takeIf { it.isNotEmpty() }
}
