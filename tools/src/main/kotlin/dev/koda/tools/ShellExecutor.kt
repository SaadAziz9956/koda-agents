package dev.koda.tools

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs a shell command and returns its combined output. The default
 * implementation runs directly; the core injects an OS-sandboxed executor
 * (Seatbelt on macOS, bubblewrap on Linux) so [BashTool] stays unaware of
 * how isolation is achieved.
 */
interface ShellExecutor {
    suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult
}

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    /** True when the failure looks like the sandbox blocked the command. */
    val sandboxDenied: Boolean = false,
)

/** Runs the command directly under `/bin/bash -c`, no isolation. */
class DirectShellExecutor : ShellExecutor {
    override suspend fun run(command: String, cwd: Path, timeoutMs: Long): ShellResult =
        runProcess(listOf("/bin/bash", "-c", command), cwd, timeoutMs)
}

/**
 * Env var names the agent's shell must never see — the harness's own
 * provider keys and common cloud/CI secrets. Matched case-insensitively as
 * substrings, so `ANTHROPIC_API_KEY`, `AWS_SECRET_ACCESS_KEY`, `GITHUB_TOKEN`,
 * etc. are all stripped. A secret can't be exfiltrated if it isn't there.
 */
private val SECRET_ENV_MARKERS =
    listOf("API_KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD", "CREDENTIAL", "ACCESS_KEY", "PRIVATE_KEY")

internal fun stripSecretEnv(env: MutableMap<String, String>) {
    env.keys.filter { key ->
        val u = key.uppercase()
        SECRET_ENV_MARKERS.any { u.contains(it) } || u.startsWith("AWS_") || u.startsWith("KODA_")
    }.toList().forEach { env.remove(it) }
}

/** Shared process launcher used by both direct and sandboxed executors. */
fun runProcess(argv: List<String>, cwd: Path, timeoutMs: Long): ShellResult {
    val builder = ProcessBuilder(argv)
        .directory(cwd.toFile())
        .redirectErrorStream(true)
    stripSecretEnv(builder.environment())
    val process = builder.start()
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
        process.destroyForcibly()
        return ShellResult(exitCode = -1, output = "", timedOut = true)
    }
    val output = process.inputStream.bufferedReader().readText()
    return ShellResult(exitCode = process.exitValue(), output = output)
}
