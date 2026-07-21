package dev.koda.core.engine

import dev.koda.tools.ShellExecutor
import dev.koda.tools.ShellResult
import dev.koda.tools.runProcess
import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * OS-level shell sandboxing. Phase 1 covers macOS via Apple Seatbelt
 * (`/usr/bin/sandbox-exec`), which is built in — nothing to install.
 *
 * The generated profile allows full read, restricts writes to the workspace
 * (cwd + subdirs) and temp, protects `.git` even inside the workspace, and
 * denies network. Paths are canonicalized (symlinks resolved) because
 * Seatbelt matches real paths — `/tmp` and `/var/folders` are symlinks.
 */

enum class SandboxPolicy {
    /** No writes, no network — safe for untrusted exploration. */
    READ_ONLY,

    /** Writes to cwd + temp, `.git` protected, network denied. The default. */
    WORKSPACE_WRITE,

    /** No sandbox — the command runs with full user privileges. */
    DANGER_FULL_ACCESS,
}

object Sandbox {
    private const val SEATBELT = "/usr/bin/sandbox-exec"

    fun isMacSeatbeltAvailable(): Boolean =
        System.getProperty("os.name").startsWith("Mac") && java.io.File(SEATBELT).canExecute()

    /** Builds the Seatbelt argv wrapping `/bin/bash -c command` for [cwd] under [policy]. */
    fun seatbeltArgv(command: String, cwd: Path, policy: SandboxPolicy): List<String> =
        listOf(SEATBELT, "-p", profile(cwd, policy), "--", "/bin/bash", "-c", command)

    private fun profile(cwd: Path, policy: SandboxPolicy): String {
        val root = canonical(cwd)
        val tmp = canonical(Path.of(System.getProperty("java.io.tmpdir")))
        val writes = when (policy) {
            SandboxPolicy.READ_ONLY -> ""
            SandboxPolicy.DANGER_FULL_ACCESS -> "(allow file-write*)\n(allow network*)"
            SandboxPolicy.WORKSPACE_WRITE -> """
                (allow file-write*
                  (subpath "$root")
                  (subpath "$tmp")
                  (subpath "/private/tmp")
                  (literal "/dev/null") (literal "/dev/stdout") (literal "/dev/stderr") (literal "/dev/tty"))
                (deny file-write* (subpath "$root/.git"))
            """.trimIndent()
        }
        return buildString {
            appendLine("(version 1)")
            appendLine("(allow default)")
            appendLine("(deny network*)")
            appendLine("(deny file-write*)")
            if (writes.isNotEmpty()) appendLine(writes)
        }
    }

    private fun canonical(p: Path): String =
        runCatching { p.toRealPath().toString() }.getOrDefault(p.absolute().normalize().toString())
}

/**
 * Runs commands sandboxed; if the sandbox blocks one, asks [approveEscalation]
 * whether to re-run it unsandboxed (the Codex/Claude Code escalation pattern).
 * Only sandbox *denials* escalate — a normal non-zero exit is returned as-is.
 */
class EscalatingShellExecutor(
    private val sandboxed: ShellExecutor,
    private val direct: ShellExecutor,
    private val approveEscalation: suspend (command: String) -> Boolean,
) : ShellExecutor {
    override suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult {
        val result = sandboxed.run(command, cwd, timeoutMs)
        if (result.sandboxDenied && approveEscalation(command)) {
            return direct.run(command, cwd, timeoutMs)
        }
        return result
    }
}

/** [ShellExecutor] that wraps commands in Seatbelt; flags likely sandbox denials. */
class SeatbeltShellExecutor(private val policy: SandboxPolicy) : ShellExecutor {
    override suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult {
        val result = runProcess(Sandbox.seatbeltArgv(command, cwd, policy), cwd, timeoutMs)
        return result.copy(sandboxDenied = looksDenied(result))
    }

    private fun looksDenied(r: ShellResult): Boolean {
        if (r.exitCode == 0 || r.timedOut) return false
        val s = r.output.lowercase()
        return "operation not permitted" in s || "read-only file system" in s ||
            "sandbox" in s || "deny(1)" in s
    }
}
