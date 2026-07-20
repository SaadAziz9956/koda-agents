package dev.koda.tools

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_TIMEOUT_MS = 120_000L
private const val MAX_TIMEOUT_MS = 600_000L
private const val MAX_OUTPUT_CHARS = 30_000

class BashTool : KodaTool {
    override val name = "bash"
    override val description =
        "Execute a shell command in the session working directory. " +
        "Returns combined stdout/stderr, truncated to $MAX_OUTPUT_CHARS chars."
    override val mutating = true
    override val parameters = objectSchema(
        required = listOf("command"),
        properties = mapOf(
            "command" to ("string" to "The shell command to execute"),
            "timeout" to ("integer" to "Timeout in milliseconds (default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS)"),
        ),
    )

    override fun summarize(args: JsonObject) = "$ ${args.requiredString("command").take(200)}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val command = args.requiredString("command")
            val timeoutMs = (args.optionalInt("timeout")?.toLong() ?: DEFAULT_TIMEOUT_MS)
                .coerceIn(1_000L, MAX_TIMEOUT_MS)

            val process = ProcessBuilder("/bin/bash", "-c", command)
                .directory(ctx.cwd.toFile())
                .redirectErrorStream(true)
                .start()

            val finished = try {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                throw e
            }

            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult.error("Command timed out after ${timeoutMs}ms: $command")
            }

            val output = process.inputStream.bufferedReader().readText()
            val truncated = if (output.length > MAX_OUTPUT_CHARS) {
                output.take(MAX_OUTPUT_CHARS) +
                    "\n… (output truncated, ${output.length} chars total)"
            } else output

            val exitCode = process.exitValue()
            val body = truncated.ifEmpty { "(no output)" }
            if (exitCode == 0) ToolResult(body)
            else ToolResult("Exit code $exitCode\n$body", isError = true)
        }
}
