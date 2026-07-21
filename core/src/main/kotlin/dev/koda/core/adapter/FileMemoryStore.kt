package dev.koda.core.adapter

import dev.koda.tools.MemoryScope
import dev.koda.tools.MemoryStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Memory on disk under the Koda home. USER memory is global (`USER.md`);
 * PROJECT memory is keyed by the working directory (`memory/<key>.md`), so
 * each project accumulates its own facts.
 */
class FileMemoryStore(kodaHome: Path, cwd: Path) : MemoryStore {
    private val userFile: Path = kodaHome.resolve("USER.md")
    private val projectFile: Path = kodaHome.resolve("memory").resolve(projectKey(cwd) + ".md")

    private fun fileFor(scope: MemoryScope) = if (scope == MemoryScope.USER) userFile else projectFile

    override fun load(scope: MemoryScope): String {
        val f = fileFor(scope)
        return if (f.exists()) runCatching { f.readText().trim() }.getOrDefault("") else ""
    }

    override fun append(scope: MemoryScope, entry: String) {
        val f = fileFor(scope)
        f.createParentDirectories()
        val bullet = if (entry.trimStart().startsWith("-")) entry else "- $entry"
        Files.writeString(f, bullet.trimEnd() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    override fun replace(scope: MemoryScope, content: String) {
        val f = fileFor(scope)
        f.createParentDirectories()
        f.writeText(content.trimEnd() + "\n")
    }

    private fun projectKey(cwd: Path): String {
        val canonical = runCatching { cwd.toRealPath().toString() }.getOrDefault(cwd.toString())
        // Stable, filesystem-safe key from the path.
        return Integer.toHexString(canonical.hashCode()) + "-" + cwd.fileName
    }
}
