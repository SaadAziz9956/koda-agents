package dev.koda.core.engine

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

private const val MAX_FILES = 5
private const val MAX_BYTES = 20_000

/**
 * Expand `@path` mentions in a user message: any token that resolves to a real
 * file under [cwd] gets its contents appended to the turn so the model has them
 * in context. Tokens that aren't files (and things like emails) are left alone.
 * Done in the core so every surface — CLI, TUI, desktop, gateway — gets it.
 */
fun expandFileMentions(text: String, cwd: Path): String {
    val base = cwd.toAbsolutePath().normalize()
    val seen = LinkedHashMap<String, String>()
    // `@` not preceded by a word char (skips emails like a@b), path-ish token after.
    Regex("(?<![\\w])@([\\w./\\-]+)").findAll(text).forEach { m ->
        if (seen.size >= MAX_FILES) return@forEach
        val rel = m.groupValues[1]
        if (seen.containsKey(rel)) return@forEach
        val p = base.resolve(rel).normalize()
        if (!p.startsWith(base) || !p.isRegularFile()) return@forEach
        val content = runCatching {
            String(Files.newInputStream(p).use { it.readNBytes(MAX_BYTES) }, Charsets.UTF_8)
        }.getOrNull() ?: return@forEach
        seen[rel] = content
    }
    if (seen.isEmpty()) return text
    return buildString {
        append(text).append("\n\n---\nReferenced files:\n")
        for ((rel, content) in seen) append("\n@").append(rel).append(":\n```\n").append(content).append("\n```\n")
    }
}
