package dev.koda.core

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Loads and merges project context/instruction files into one ordered,
 * budget-bounded block for the system prompt, and (separately) discovers
 * context files in subdirectories on demand as the agent works there.
 *
 * Discovery (startup): the global file (`~/.koda/KODA.md`) plus, for every
 * directory from the git root down to cwd, the first match of `KODA.md`,
 * `AGENTS.md`, `CLAUDE.md` (one per directory) followed by the matching
 * `*.local.md` (gitignored personal overrides). Reading `AGENTS.md`/
 * `CLAUDE.md` is cross-harness interop — Codex and Hermes read them too.
 *
 * Merge: concatenated broadest-first (global → repo root → cwd) so the most
 * local file is read last and takes soft precedence. Frozen in the system
 * prompt at session start — design law #2, cache stability.
 *
 * `@import` lines expand referenced files inline at load (max depth 4,
 * skipping fenced code blocks), resolved relative to the importing file.
 *
 * Budget: a total character cap spent **leaf-first**, so cwd-local
 * instructions are never starved by an oversized global file. Overflow keeps
 * head + tail with a drop marker.
 *
 * Subtree files (below cwd) are NOT in the startup block — they load lazily
 * via [subtreeLayers] and are appended to tool results, never the frozen
 * system prompt, so the prompt-cache prefix stays stable.
 */
object ContextFiles {

    private const val TOTAL_BUDGET_CHARS = 32_000
    private const val MIN_SLICE_CHARS = 500
    private const val SUBTREE_CAP_CHARS = 8_000
    private const val MAX_IMPORT_DEPTH = 4
    private const val HEAD_RATIO = 0.7
    private const val TAIL_RATIO = 0.2
    private val FILE_NAMES = listOf("KODA.md", "AGENTS.md", "CLAUDE.md")
    private val LOCAL_NAMES = listOf("KODA.local.md", "AGENTS.local.md", "CLAUDE.local.md")

    data class Layer(val label: String, val content: String)

    fun load(kodaHome: Path, cwd: Path): List<Layer> {
        val ordered = buildList {
            globalLayer(kodaHome)?.let(::add)
            addAll(directoryChain(cwd))
        }
        return applyBudgetLeafFirst(ordered)
    }

    /**
     * Context files in directories strictly **below** cwd, on the path from
     * cwd down to [touchedFile]'s directory, that have not been surfaced yet
     * this session. Marks them in [seen] so each loads at most once.
     */
    fun subtreeLayers(cwd: Path, touchedFile: Path, seen: MutableSet<Path>): List<Layer> {
        val absCwd = cwd.toAbsolutePath().normalize()
        val startDir = touchedFile.toAbsolutePath().normalize().let { if (it.isRegularFile()) it.parent else it }
            ?: return emptyList()
        if (startDir == absCwd || !startDir.startsWith(absCwd)) return emptyList()

        val dirs = mutableListOf<Path>()
        var dir: Path? = startDir
        while (dir != null && dir != absCwd && dir.startsWith(absCwd)) {
            dirs.add(dir)
            dir = dir.parent
        }
        dirs.reverse() // shallowest → deepest (most specific last)

        return dirs.flatMap { d ->
            if (!seen.add(d)) return@flatMap emptyList()
            dirLayers(d, absCwd, cap = SUBTREE_CAP_CHARS)
        }
    }

    // ---- startup discovery -------------------------------------------------

    private fun globalLayer(kodaHome: Path): Layer? =
        FILE_NAMES.firstNotNullOfOrNull { name ->
            val file = kodaHome.resolve(name)
            if (file.exists()) readExpanded(file, depth = 0)?.let { Layer("global: ~/.koda/$name", it) } else null
        }

    /** Main + local file(s) per directory, from the git root down to cwd. */
    private fun directoryChain(cwd: Path): List<Layer> {
        val absCwd = cwd.toAbsolutePath().normalize()
        val dirs = mutableListOf<Path>()
        var dir: Path? = absCwd
        while (dir != null) {
            dirs.add(dir)
            if (dir.resolve(".git").exists()) break // stop at the repo boundary
            dir = dir.parent
        }
        dirs.reverse() // root → cwd
        return dirs.flatMap { dirLayers(it, absCwd, cap = Int.MAX_VALUE) }
    }

    /** First-match main file then first-match local file for one directory. */
    private fun dirLayers(dir: Path, absCwd: Path, cap: Int): List<Layer> {
        fun label(name: String) = if (dir == absCwd) name else "$dir/$name"
        return buildList {
            firstMatch(dir, FILE_NAMES)?.let { (name, content) ->
                add(Layer(label(name), content.take(cap)))
            }
            firstMatch(dir, LOCAL_NAMES)?.let { (name, content) ->
                add(Layer(label(name), content.take(cap)))
            }
        }
    }

    private fun firstMatch(dir: Path, names: List<String>): Pair<String, String>? =
        names.firstNotNullOfOrNull { name ->
            val file = dir.resolve(name)
            if (file.exists()) readExpanded(file, depth = 0)?.let { name to it } else null
        }

    // ---- @import expansion -------------------------------------------------

    private val IMPORT_TOKEN = Regex("""(^|\s)@(\S+)""")

    private fun readExpanded(file: Path, depth: Int, visited: Set<Path> = emptySet()): String? {
        val abs = file.toAbsolutePath().normalize()
        if (abs in visited) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        val expanded = expandImports(raw, abs.parent, depth, visited + abs)
        return expanded.trim().takeIf { it.isNotEmpty() }
    }

    private fun expandImports(text: String, baseDir: Path?, depth: Int, visited: Set<Path>): String {
        if (depth >= MAX_IMPORT_DEPTH || baseDir == null || '@' !in text) return text
        var inFence = false
        return text.lineSequence().joinToString("\n") { line ->
            if (line.trimStart().startsWith("```")) { inFence = !inFence; return@joinToString line }
            if (inFence || '@' !in line) return@joinToString line
            IMPORT_TOKEN.replace(line) { m ->
                val target = resolveImport(baseDir, m.groupValues[2])
                val imported = target?.let { readExpanded(it, depth + 1, visited) }
                if (imported != null) m.groupValues[1] + imported else m.value
            }
        }
    }

    private fun resolveImport(baseDir: Path, token: String): Path? {
        val path = when {
            token.startsWith("~/") -> Path.of(System.getProperty("user.home"), token.removePrefix("~/"))
            Path.of(token).isAbsolute -> Path.of(token)
            else -> baseDir.resolve(token)
        }.normalize()
        return path.takeIf { it.exists() && it.isRegularFile() }
    }

    // ---- budget ------------------------------------------------------------

    private fun applyBudgetLeafFirst(ordered: List<Layer>): List<Layer> {
        var remaining = TOTAL_BUDGET_CHARS
        val kept = ArrayDeque<Layer>()
        for (layer in ordered.asReversed()) {
            if (remaining < MIN_SLICE_CHARS) break
            val slice = if (layer.content.length <= remaining) layer.content else truncate(layer.content, remaining)
            kept.addFirst(layer.copy(content = slice))
            remaining -= slice.length
        }
        return kept.toList()
    }

    private fun truncate(content: String, budget: Int): String {
        val marker = "\n… [trimmed to fit context budget] …\n"
        val room = (budget - marker.length).coerceAtLeast(0)
        if (room <= 0) return content.take(budget)
        val head = (room * HEAD_RATIO).toInt()
        val tail = (room * TAIL_RATIO).toInt()
        return content.take(head) + marker + content.takeLast(tail)
    }
}
