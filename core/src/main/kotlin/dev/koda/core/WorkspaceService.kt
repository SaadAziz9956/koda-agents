package dev.koda.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Serves the workspace tree and file contents to remote clients (the desktop
 * file explorer). Every path is confined under the workspace root — a client
 * cannot escape it with `..` — since the daemon may be reachable over the
 * network.
 */
object WorkspaceService {
    private const val MAX_FILE = 500_000
    private val SKIP = setOf(".git", "build", ".gradle", "node_modules", ".idea", ".koda", "out")

    data class Entry(val name: String, val relPath: String, val isDir: Boolean)

    /** Resolve [rel] under [root], or null if it escapes the root. */
    private fun resolve(root: Path, rel: String?): Path? {
        val base = root.toAbsolutePath().normalize()
        val target = (if (rel.isNullOrBlank()) base else base.resolve(rel)).normalize()
        return if (target.startsWith(base)) target else null
    }

    private fun relOf(root: Path, p: Path): String =
        root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString()

    /** List a directory; null if it escapes the root or isn't a directory. */
    fun list(root: Path, rel: String?): Pair<String, List<Entry>>? {
        val dir = resolve(root, rel) ?: return null
        if (!dir.isDirectory()) return null
        val entries = runCatching {
            Files.list(dir).use { stream ->
                stream.toList()
                    .filter { it.name !in SKIP }
                    .map { Entry(it.name, relOf(root, it), it.isDirectory()) }
            }
        }.getOrDefault(emptyList())
        val sorted = entries.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        return relOf(root, dir).ifEmpty { "" } to sorted
    }

    data class FileRead(val content: String, val truncated: Boolean, val error: String?)

    fun read(root: Path, rel: String): FileRead {
        val f = resolve(root, rel) ?: return FileRead("", false, "path escapes the workspace")
        if (!f.isRegularFile()) return FileRead("", false, "not a file")
        val size = runCatching { Files.size(f) }.getOrDefault(0L)
        return runCatching {
            if (size > MAX_FILE) {
                val head = Files.newInputStream(f).use { it.readNBytes(MAX_FILE) }.toString(Charsets.UTF_8)
                FileRead(head, true, null)
            } else {
                FileRead(Files.readString(f), false, null)
            }
        }.getOrElse { FileRead("", false, "unreadable (binary?): ${it.message}") }
    }
}
