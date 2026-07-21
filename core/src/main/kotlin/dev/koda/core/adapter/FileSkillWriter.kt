package dev.koda.core.adapter

import dev.koda.tools.SkillWriter
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/** Writes agent-authored skills under `<kodaHome>/skills/<name>/SKILL.md`. */
class FileSkillWriter(private val kodaHome: Path) : SkillWriter {
    override fun create(name: String, description: String, body: String): String {
        val file = kodaHome.resolve("skills").resolve(name).resolve("SKILL.md")
        if (file.exists()) return "A skill named '$name' already exists — pick another name or edit it."
        val content = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: \"${description.replace("\"", "'")}\"")
            appendLine("author: koda")
            appendLine("---")
            appendLine()
            append(body)
        }
        return runCatching {
            file.createParentDirectories()
            file.writeText(content)
            "Created skill '$name' (available next session)."
        }.getOrElse { "Failed to create skill '$name': ${it.message}" }
    }
}
