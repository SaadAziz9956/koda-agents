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
    private val BWRAP_PATHS = listOf("/usr/bin/bwrap", "/bin/bwrap", "/usr/local/bin/bwrap")

    private val osName get() = System.getProperty("os.name")

    fun isMacSeatbeltAvailable(): Boolean =
        osName.startsWith("Mac") && java.io.File(SEATBELT).canExecute()

    fun isLinuxBwrapAvailable(): Boolean =
        osName.equals("Linux", ignoreCase = true) && bwrapPath() != null

    private fun bwrapPath(): String? = BWRAP_PATHS.firstOrNull { java.io.File(it).canExecute() }

    /** Builds the Seatbelt argv wrapping `/bin/bash -c command` for [cwd] under [policy]. */
    fun seatbeltArgv(command: String, cwd: Path, policy: SandboxPolicy): List<String> =
        listOf(SEATBELT, "-p", profile(cwd, policy), "--", "/bin/bash", "-c", command)

    /**
     * Escalation profile: writes and network are allowed (what an approved
     * escalation needs), but secret reads stay denied — secrets are a hard
     * floor that escalation never lifts.
     */
    fun escalatedSeatbeltArgv(command: String, cwd: Path): List<String> {
        val p = buildString {
            appendLine("(version 1)")
            appendLine("(allow default)")
            appendLine(secretReadDenies())
        }
        return listOf(SEATBELT, "-p", p, "--", "/bin/bash", "-c", command)
    }

    /** Bubblewrap escalation: writable root + network, but secrets still masked. */
    fun escalatedBwrapArgv(command: String, cwd: Path): List<String> {
        val bwrap = bwrapPath() ?: error("bwrap not available")
        val root = canonical(cwd)
        return buildList {
            add(bwrap)
            add("--bind"); add("/"); add("/") // writable root (escalation)
            add("--dev"); add("/dev"); add("--proc"); add("/proc")
            add("--unshare-user"); add("--unshare-pid"); add("--die-with-parent")
            add("--chdir"); add(root)
            addAll(bwrapSecretMasks(root)) // secrets stay masked
            // network allowed (no --unshare-net)
            add("--"); add("/bin/bash"); add("-c"); add(command)
        }
    }

    /**
     * Builds the bubblewrap argv for Linux: read-only root, cwd (and temp)
     * bound writable under workspace-write, `.git` re-bound read-only, and the
     * network namespace unshared (network denied). Mirrors the Seatbelt policy.
     */
    fun bwrapArgv(command: String, cwd: Path, policy: SandboxPolicy): List<String> {
        val bwrap = bwrapPath() ?: error("bwrap not available")
        val root = canonical(cwd)
        val tmp = canonical(Path.of(System.getProperty("java.io.tmpdir")))
        return buildList {
            add(bwrap)
            add("--ro-bind"); add("/"); add("/")
            add("--dev"); add("/dev")
            add("--proc"); add("/proc")
            add("--unshare-user"); add("--unshare-pid")
            add("--die-with-parent")
            add("--chdir"); add(root)
            if (policy == SandboxPolicy.WORKSPACE_WRITE) {
                add("--bind"); add(root); add(root)
                add("--bind"); add("/tmp"); add("/tmp")
                if (tmp != "/tmp") { add("--bind"); add(tmp); add(tmp) }
                val git = "$root/.git"
                if (java.io.File(git).exists()) { add("--ro-bind"); add(git); add(git) }
            }
            addAll(bwrapSecretMasks(root))
            // Both non-danger policies deny network:
            add("--unshare-net")
            add("--"); add("/bin/bash"); add("-c"); add(command)
        }
    }

    /** Home-relative paths whose contents must never be read by a tool. */
    private val SECRET_SUBPATHS = listOf(
        ".ssh", ".aws", ".gnupg", ".config/gcloud", ".kube", ".docker",
        ".netrc", ".npmrc", ".pypirc", ".git-credentials", ".config/koda", ".koda",
    )

    /** Deny-read SBPL for secrets: cloud/SSH/credential dirs + any `.env` file. */
    private fun secretReadDenies(): String {
        val home = canonical(Path.of(System.getProperty("user.home")))
        val subpaths = SECRET_SUBPATHS.joinToString("\n") { """(deny file-read* (subpath "$home/$it"))""" }
        // Any path ending in /.env or /.env.<something>, anywhere (incl. the workspace).
        val envRegex = """(deny file-read* (regex #"/\.env(\.[^/]*)?$"))"""
        return "$subpaths\n$envRegex"
    }

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
            if (policy != SandboxPolicy.DANGER_FULL_ACCESS) appendLine(secretReadDenies())
            appendLine("(deny file-write*)")
            if (writes.isNotEmpty()) appendLine(writes)
        }
    }

    /** bwrap args that mask secret dirs (empty tmpfs) and files (/dev/null). */
    private fun bwrapSecretMasks(root: String): List<String> = buildList {
        val home = System.getProperty("user.home")
        for (sub in SECRET_SUBPATHS) {
            val p = "$home/$sub"
            if (java.io.File(p).isDirectory) { add("--tmpfs"); add(p) }
            else if (java.io.File(p).exists()) { add("--ro-bind"); add("/dev/null"); add(p) }
        }
        java.io.File("$root/.env").takeIf { it.exists() }?.let { add("--ro-bind"); add("/dev/null"); add("$root/.env") }
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

/** [ShellExecutor] that wraps commands in Seatbelt (macOS); flags likely denials. */
class SeatbeltShellExecutor(private val policy: SandboxPolicy) : ShellExecutor {
    override suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult =
        runProcess(Sandbox.seatbeltArgv(command, cwd, policy), cwd, timeoutMs)
            .let { it.copy(sandboxDenied = looksSandboxDenied(it)) }
}

/** [ShellExecutor] that wraps commands in bubblewrap (Linux); flags likely denials. */
class BwrapShellExecutor(private val policy: SandboxPolicy) : ShellExecutor {
    override suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult =
        runProcess(Sandbox.bwrapArgv(command, cwd, policy), cwd, timeoutMs)
            .let { it.copy(sandboxDenied = looksSandboxDenied(it)) }
}

/**
 * The escalation target: a relaxed sandbox that allows writes + network but
 * still denies secret reads (and env stays stripped via runProcess). Escalation
 * widens access without ever exposing secrets — secrets are a hard floor.
 */
class EscalatedShellExecutor : ShellExecutor {
    override suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult {
        val argv = when {
            Sandbox.isMacSeatbeltAvailable() -> Sandbox.escalatedSeatbeltArgv(command, cwd)
            Sandbox.isLinuxBwrapAvailable() -> Sandbox.escalatedBwrapArgv(command, cwd)
            else -> listOf("/bin/bash", "-c", command)
        }
        return runProcess(argv, cwd, timeoutMs)
    }
}

/** Heuristic: does a failed command look blocked by the sandbox (vs a normal error)? */
private fun looksSandboxDenied(r: ShellResult): Boolean {
    if (r.exitCode == 0 || r.timedOut) return false
    val s = r.output.lowercase()
    return "operation not permitted" in s || "read-only file system" in s ||
        "permission denied" in s || "sandbox" in s || "deny(1)" in s ||
        "network is unreachable" in s || "could not resolve host" in s
}
