package dev.koda.core

import ai.koog.prompt.Prompt
import java.nio.file.Files
import java.nio.file.Path

/**
 * In-memory undo history for one session, powering /rewind. A checkpoint is
 * opened at the start of each turn holding the pre-turn conversation snapshot
 * (Koog [Prompt]s are immutable, so the reference is the snapshot) and a
 * copy-on-write record of files the turn's write/edit tools touch — each
 * captured once, with its bytes before the change (or `null` if it did not yet
 * exist).
 *
 * Scope note: only structured file edits (write/edit tools) are tracked;
 * changes made through the bash tool are not, and rewind will not undo them.
 * History lives in the running core, not on disk.
 */
class SessionCheckpoints(private val max: Int = 50) {

    data class RewindOutcome(val steps: Int, val promptBefore: Prompt?, val filesRestored: List<String>)

    private class Checkpoint(val promptBefore: Prompt?, val label: String) {
        // null value = the path did not exist before this turn.
        val files = LinkedHashMap<Path, ByteArray?>()
    }

    private val stack = ArrayDeque<Checkpoint>()
    private var current: Checkpoint? = null

    /** Open a checkpoint for a turn about to run. */
    @Synchronized
    fun begin(promptBefore: Prompt?, label: String) {
        val cp = Checkpoint(promptBefore, label.trim().take(80))
        stack.addLast(cp)
        current = cp
        while (stack.size > max) stack.removeFirst()
    }

    /** Record a file's prior state, once per checkpoint. Invoked by the tool sink. */
    @Synchronized
    fun capture(path: Path) {
        val cp = current ?: return
        val key = path.normalize()
        if (cp.files.containsKey(key)) return
        cp.files[key] = if (Files.exists(key)) runCatching { Files.readAllBytes(key) }.getOrNull() else null
    }

    @Synchronized
    fun depth(): Int = stack.size

    /**
     * Undo the last [steps] turns. Restores each touched file to its state
     * before the earliest undone turn and returns the conversation snapshot to
     * roll back to. Returns null when there is nothing to undo.
     */
    @Synchronized
    fun rewind(steps: Int): RewindOutcome? {
        if (stack.isEmpty()) return null
        val n = steps.coerceIn(1, stack.size)
        var earliest: Checkpoint? = null
        val restored = LinkedHashSet<String>()
        // Newest → oldest: for a path touched in several undone turns, the
        // oldest capture is written last and wins, yielding the pre-earliest state.
        repeat(n) {
            val cp = stack.removeLast()
            earliest = cp
            for ((path, bytes) in cp.files) {
                runCatching {
                    if (bytes == null) {
                        Files.deleteIfExists(path)
                    } else {
                        path.parent?.let { Files.createDirectories(it) }
                        Files.write(path, bytes)
                    }
                    restored.add(path.toString())
                }
            }
        }
        current = null
        return RewindOutcome(n, earliest?.promptBefore, restored.toList())
    }
}
