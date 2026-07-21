package dev.koda.core

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Loads and merges project context/instruction files into one ordered,
 * budget-bounded block for the system prompt.
 *
 * Discovery: the global file (`~/.koda/KODA.md`) plus, for every directory
 * from the git root down to cwd, the first match of `KODA.md`, `AGENTS.md`,
 * `CLAUDE.md` (one per directory). Reading `AGENTS.md`/`CLAUDE.md` is
 * cross-harness interop — Codex and Hermes read them too.
 *
 * Merge: concatenated broadest-first (global → repo root → cwd) so the most
 * local file is read last and takes soft precedence (the Codex/Claude Code
 * consensus). Assembled once at session start and frozen in the system
 * prompt — design law #2, prompt-cache stability.
 *
 * Budget: a total character cap consumed **leaf-first**, so cwd-local
 * instructions are reserved before an oversized global file can starve them
 * (fixing Codex's root-first budget flaw). An oversized file keeps its head
 * and tail with a drop marker in between.
 */
object ContextFiles {

    private const val TOTAL_BUDGET_CHARS = 32_000
    private const val MIN_SLICE_CHARS = 500
    private const val HEAD_RATIO = 0.7
    private const val TAIL_RATIO = 0.2
    private val FILE_NAMES = listOf("KODA.md", "AGENTS.md", "CLAUDE.md")

    data class Layer(val label: String, val content: String)

    fun load(kodaHome: Path, cwd: Path): List<Layer> {
        // Broadest-first: global, then git-root down to cwd.
        val ordered = buildList {
            globalLayer(kodaHome)?.let(::add)
            addAll(directoryChain(cwd))
        }

        // Spend the budget leaf-first so local content is never starved, then
        // restore broadest-first order for the prompt.
        var remaining = TOTAL_BUDGET_CHARS
        val kept = ArrayDeque<Layer>()
        for (layer in ordered.asReversed()) {
            if (remaining < MIN_SLICE_CHARS) break
            val slice = if (layer.content.length <= remaining) layer.content
            else truncate(layer.content, remaining)
            kept.addFirst(layer.copy(content = slice))
            remaining -= slice.length
        }
        return kept.toList()
    }

    private fun globalLayer(kodaHome: Path): Layer? =
        FILE_NAMES.firstNotNullOfOrNull { name ->
            val file = kodaHome.resolve(name)
            if (file.exists()) readOrNull(file)?.let { Layer("global: ~/.koda/$name", it) } else null
        }

    /** First-match file per directory, from the git root down to cwd. */
    private fun directoryChain(cwd: Path): List<Layer> {
        val dirs = mutableListOf<Path>()
        var dir: Path? = cwd.toAbsolutePath().normalize()
        while (dir != null) {
            dirs.add(dir)
            if (dir.resolve(".git").exists()) break // stop at the repo boundary
            dir = dir.parent
        }
        dirs.reverse() // root → cwd

        val absCwd = cwd.toAbsolutePath().normalize()
        return dirs.mapNotNull { d ->
            FILE_NAMES.firstNotNullOfOrNull { name ->
                val file = d.resolve(name)
                if (!file.exists()) return@firstNotNullOfOrNull null
                readOrNull(file)?.let { content ->
                    val label = if (d == absCwd) name else "$d/$name"
                    Layer(label, content)
                }
            }
        }
    }

    private fun truncate(content: String, budget: Int): String {
        val marker = "\n… [trimmed to fit context budget] …\n"
        val room = (budget - marker.length).coerceAtLeast(0)
        if (room <= 0) return content.take(budget)
        val head = (room * HEAD_RATIO).toInt()
        val tail = (room * TAIL_RATIO).toInt()
        return content.take(head) + marker + content.takeLast(tail)
    }

    private fun readOrNull(file: Path): String? =
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
