package dev.koda.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val DEFAULT_TIMEOUT_MS = 120_000L
private const val MAX_TIMEOUT_MS = 600_000L
private const val MAX_OUTPUT_CHARS = 30_000

class BashTool(
    private val executor: ShellExecutor = DirectShellExecutor(),
) : KodaTool {
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

            val result = executor.run(command, ctx.cwd, timeoutMs)
            when {
                result.timedOut -> ToolResult.error("Command timed out after ${timeoutMs}ms: $command")
                else -> {
                    val body = result.output.let {
                        if (it.length > MAX_OUTPUT_CHARS) it.take(MAX_OUTPUT_CHARS) +
                            "\n… (output truncated, ${it.length} chars total)" else it
                    }.ifEmpty { "(no output)" }
                    if (result.exitCode == 0) ToolResult(body)
                    else ToolResult("Exit code ${result.exitCode}\n$body", isError = true)
                }
            }
        }
}
