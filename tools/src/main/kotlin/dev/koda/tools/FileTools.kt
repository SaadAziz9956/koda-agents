package dev.koda.tools

import java.nio.file.Files
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.JsonObject

private const val MAX_READ_LINES = 2000
private const val MAX_LINE_CHARS = 2000

class ReadTool : KodaTool {
    override val name = "read"
    override val description =
        "Read a file from the filesystem. Returns line-numbered content. " +
        "Use offset/limit for large files."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("file_path"),
        properties = mapOf(
            "file_path" to ("string" to "Absolute or cwd-relative path to the file"),
            "offset" to ("integer" to "1-based line number to start reading from"),
            "limit" to ("integer" to "Maximum number of lines to read (default $MAX_READ_LINES)"),
        ),
    )

    override fun summarize(args: JsonObject) = "read ${args.requiredString("file_path")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = ctx.resolve(args.requiredString("file_path"))
        if (!path.exists()) return ToolResult.error("File not found: $path")
        if (!path.isRegularFile()) return ToolResult.error("Not a regular file: $path")

        val offset = (args.optionalInt("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.optionalInt("limit") ?: MAX_READ_LINES).coerceIn(1, MAX_READ_LINES)

        val lines = Files.readAllLines(path)
        if (lines.isEmpty()) {
            ctx.readFiles.add(path)
            return ToolResult("(empty file)")
        }
        val slice = lines.drop(offset - 1).take(limit)
        val numbered = slice.mapIndexed { i, line ->
            val n = offset + i
            val clipped = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…" else line
            "%6d\t%s".format(n, clipped)
        }
        val suffix = if (offset - 1 + slice.size < lines.size) {
            "\n… (${lines.size} lines total, showing $offset-${offset - 1 + slice.size})"
        } else ""

        ctx.readFiles.add(path)
        return ToolResult(numbered.joinToString("\n") + suffix)
    }
}

class WriteTool : KodaTool {
    override val name = "write"
    override val description =
        "Write content to a file, creating it (and parent directories) if needed, " +
        "overwriting if it exists. To modify an existing file prefer the edit tool."
    override val mutating = true
    override val parameters = objectSchema(
        required = listOf("file_path", "content"),
        properties = mapOf(
            "file_path" to ("string" to "Absolute or cwd-relative path to the file"),
            "content" to ("string" to "Full content to write"),
        ),
    )

    override fun summarize(args: JsonObject) = "write ${args.requiredString("file_path")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = ctx.resolve(args.requiredString("file_path"))
        val content = args.requiredString("content")

        if (path.exists() && path !in ctx.readFiles) {
            return ToolResult.error(
                "Refusing to overwrite $path: read it first so you know what you are replacing."
            )
        }
        val previous = if (path.exists()) path.readText() else ""
        ctx.snapshotSink?.capture(path)
        path.createParentDirectories()
        path.writeText(content)
        ctx.readFiles.add(path)
        val rel = runCatching { ctx.cwd.relativize(path).toString() }.getOrDefault(path.toString())
        return ToolResult("Wrote ${content.length} chars to $path", diff = unifiedDiff(previous, content, rel))
    }
}

class EditTool : KodaTool {
    override val name = "edit"
    override val description =
        "Edit a file by exact string replacement. old_string must match the file " +
        "exactly and be unique unless replace_all is true. Read the file first."
    override val mutating = true
    override val parameters = objectSchema(
        required = listOf("file_path", "old_string", "new_string"),
        properties = mapOf(
            "file_path" to ("string" to "Absolute or cwd-relative path to the file"),
            "old_string" to ("string" to "Exact text to replace"),
            "new_string" to ("string" to "Replacement text (must differ from old_string)"),
            "replace_all" to ("boolean" to "Replace every occurrence (default false)"),
        ),
    )

    override fun summarize(args: JsonObject) = "edit ${args.requiredString("file_path")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val path = ctx.resolve(args.requiredString("file_path"))
        val oldString = args.requiredString("old_string")
        val newString = args.requiredString("new_string")
        val replaceAll = args.optionalBoolean("replace_all")

        if (!path.exists()) return ToolResult.error("File not found: $path")
        if (path !in ctx.readFiles) {
            return ToolResult.error("Read $path before editing it.")
        }
        if (oldString == newString) return ToolResult.error("old_string and new_string are identical")

        val content = path.readText()
        val occurrences = content.windowedOccurrences(oldString)
        if (occurrences == 0) {
            return ToolResult.error("old_string not found in $path — it must match exactly, including whitespace.")
        }
        if (occurrences > 1 && !replaceAll) {
            return ToolResult.error(
                "old_string matches $occurrences times in $path. Add surrounding context to make it unique, or set replace_all."
            )
        }

        val updated = if (replaceAll) content.replace(oldString, newString)
        else content.replaceFirst(oldString, newString)
        ctx.snapshotSink?.capture(path)
        path.writeText(updated)

        val n = if (replaceAll) occurrences else 1
        val rel = runCatching { ctx.cwd.relativize(path).toString() }.getOrDefault(path.toString())
        return ToolResult(
            "Replaced $n occurrence${if (n == 1) "" else "s"} in $path",
            diff = unifiedDiff(content, updated, rel),
        )
    }

    private fun String.windowedOccurrences(needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}
