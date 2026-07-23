package dev.koda.core

import java.nio.file.Path

/**
 * Thin git front-end the daemon exposes to clients (the desktop file/diff/commit
 * panels). Runs real `git` in the workspace via ProcessBuilder — user-initiated
 * reads and commits, not agent tool calls, so it is intentionally outside the
 * agent sandbox.
 */
object GitService {
    data class Status(
        val ok: Boolean,
        val branch: String,
        val ahead: Int,
        val behind: Int,
        val files: List<FileChange>,
    )
    data class FileChange(val path: String, val status: String, val staged: Boolean)

    private fun run(cwd: Path, vararg args: String): Pair<Int, String> = try {
        val p = ProcessBuilder(listOf("git", *args))
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        p.exitValue() to out
    } catch (e: Exception) {
        -1 to (e.message ?: "git failed")
    }

    fun status(cwd: Path): Status {
        val (code, out) = run(cwd, "status", "--porcelain=v1", "-b")
        if (code != 0) return Status(false, "", 0, 0, emptyList())
        var branch = ""; var ahead = 0; var behind = 0
        val files = ArrayList<FileChange>()
        for (line in out.lines()) {
            if (line.isEmpty()) continue
            if (line.startsWith("## ")) {
                val b = line.removePrefix("## ")
                branch = b.substringBefore("...").substringBefore(" ")
                Regex("ahead (\\d+)").find(b)?.let { ahead = it.groupValues[1].toInt() }
                Regex("behind (\\d+)").find(b)?.let { behind = it.groupValues[1].toInt() }
            } else if (line.length > 3) {
                val x = line[0]; val y = line[1]; val path = line.substring(3)
                files.add(FileChange(path, statusName(x, y), staged = x != ' ' && x != '?'))
            }
        }
        return Status(true, branch.ifBlank { "(detached)" }, ahead, behind, files)
    }

    private fun statusName(x: Char, y: Char): String = when {
        x == '?' && y == '?' -> "untracked"
        x == 'A' || y == 'A' -> "added"
        x == 'D' || y == 'D' -> "deleted"
        x == 'R' -> "renamed"
        else -> "modified"
    }

    fun diff(cwd: Path, path: String?, staged: Boolean): String {
        val args = buildList {
            add("diff"); add("--no-color")
            if (staged) add("--staged")
            if (path != null) { add("--"); add(path) }
        }
        return run(cwd, *args.toTypedArray()).second
    }

    /** The diff to review for a [target]: uncommitted / staged / a ref / a range. */
    fun reviewDiff(cwd: Path, target: String): String = when {
        target.isBlank() || target == "uncommitted" -> run(cwd, "diff", "--no-color", "HEAD").second
        target == "staged" -> run(cwd, "diff", "--no-color", "--staged").second
        target.contains("..") -> run(cwd, "diff", "--no-color", target).second
        else -> run(cwd, "show", "--no-color", target).second
            .ifBlank { run(cwd, "diff", "--no-color", "$target...HEAD").second }
    }

    fun commit(cwd: Path, message: String): Pair<Boolean, String> {
        val add = run(cwd, "add", "-A")
        if (add.first != 0) return false to add.second.trim().ifBlank { "git add failed" }
        val (code, out) = run(cwd, "commit", "-m", message)
        return (code == 0) to out.trim().lines().firstOrNull().orEmpty()
            .ifBlank { if (code == 0) "committed" else "commit failed" }
    }

    /** Push the current branch and open a PR with the GitHub CLI. */
    fun createPr(cwd: Path, title: String, body: String): Pair<Boolean, String> {
        val branch = run(cwd, "rev-parse", "--abbrev-ref", "HEAD").second.trim()
        val push = ProcessBuilder("git", "push", "-u", "origin", branch)
            .directory(cwd.toFile()).redirectErrorStream(true).start()
        push.inputStream.bufferedReader().readText(); push.waitFor()
        return try {
            val p = ProcessBuilder("gh", "pr", "create", "--title", title, "--body", body.ifBlank { title })
                .directory(cwd.toFile()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            (p.exitValue() == 0) to (out.lines().lastOrNull { it.startsWith("http") } ?: out).ifBlank { "PR created" }
        } catch (e: Exception) {
            false to "gh CLI not available: ${e.message}"
        }
    }
}
