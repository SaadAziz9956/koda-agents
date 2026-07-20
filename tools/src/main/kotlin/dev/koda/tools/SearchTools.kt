package dev.koda.tools

import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private const val MAX_MATCHES = 200
private val SKIPPED_DIRS = setOf(".git", ".gradle", "build", "node_modules", ".idea", "target", ".venv")

class GrepTool : KodaTool {
    override val name = "grep"
    override val description =
        "Search file contents with a regular expression. Uses ripgrep when available. " +
        "Returns matching lines as path:line:content, capped at $MAX_MATCHES matches."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("pattern"),
        properties = mapOf(
            "pattern" to ("string" to "Regular expression to search for"),
            "path" to ("string" to "Directory or file to search (default: cwd)"),
            "glob" to ("string" to "Filter files by glob, e.g. *.kt"),
        ),
    )

    override fun summarize(args: JsonObject) = "grep '${args.requiredString("pattern").take(80)}'"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val pattern = args.requiredString("pattern")
            val root = ctx.resolve(args.optionalString("path") ?: ".")
            val glob = args.optionalString("glob")
            if (!root.exists()) return@withContext ToolResult.error("Path not found: $root")

            ripgrep(pattern, root, glob) ?: kotlinGrep(pattern, root, glob)
        }

    private fun ripgrep(pattern: String, root: Path, glob: String?): ToolResult? {
        val rg = listOf("/opt/homebrew/bin/rg", "/usr/local/bin/rg", "rg")
            .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() }.getOrNull() == 0 }
            ?: return null

        val cmd = buildList {
            add(rg); add("--line-number"); add("--no-heading"); add("--max-count"); add("50")
            if (glob != null) { add("--glob"); add(glob) }
            add("--"); add(pattern); add(root.toString())
        }
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        return when {
            exit == 0 -> ToolResult(output.lines().take(MAX_MATCHES).joinToString("\n").trimEnd())
            exit == 1 -> ToolResult("No matches found")
            else -> ToolResult.error("ripgrep failed: ${output.take(500)}")
        }
    }

    private fun kotlinGrep(pattern: String, root: Path, glob: String?): ToolResult {
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return ToolResult.error("Invalid regex: ${e.message}")
        }
        val globMatcher = glob?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val matches = mutableListOf<String>()

        walkFiles(root) { file ->
            if (globMatcher != null && !globMatcher.matches(file.fileName)) return@walkFiles true
            runCatching {
                Files.newBufferedReader(file).useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (matches.size >= MAX_MATCHES) return@useLines
                        if (regex.containsMatchIn(line)) matches += "$file:${i + 1}:${line.take(300)}"
                    }
                }
            }
            matches.size < MAX_MATCHES
        }
        return if (matches.isEmpty()) ToolResult("No matches found")
        else ToolResult(matches.joinToString("\n"))
    }
}

class GlobTool : KodaTool {
    override val name = "glob"
    override val description =
        "Find files by glob pattern, e.g. **/*.kt. Returns matching paths sorted by modification time."
    override val mutating = false
    override val parameters = objectSchema(
        required = listOf("pattern"),
        properties = mapOf(
            "pattern" to ("string" to "Glob pattern to match file paths against"),
            "path" to ("string" to "Directory to search (default: cwd)"),
        ),
    )

    override fun summarize(args: JsonObject) = "glob ${args.requiredString("pattern")}"

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val pattern = args.requiredString("pattern")
            val root = ctx.resolve(args.optionalString("path") ?: ".")
            if (!root.exists()) return@withContext ToolResult.error("Path not found: $root")

            val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
            val matches = mutableListOf<Path>()
            walkFiles(root) { file ->
                if (matcher.matches(root.relativize(file))) matches.add(file)
                matches.size < 1000
            }

            if (matches.isEmpty()) ToolResult("No files match $pattern")
            else ToolResult(
                matches
                    .sortedByDescending { runCatching { Files.getLastModifiedTime(it) }.getOrNull() }
                    .take(MAX_MATCHES)
                    .joinToString("\n")
            )
        }
}

/** Depth-first file walk skipping VCS/build directories; [visit] returns false to stop. */
private fun walkFiles(root: Path, visit: (Path) -> Boolean) {
    if (root.isRegularFile()) {
        visit(root)
        return
    }
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir.name in SKIPPED_DIRS) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (visit(file)) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

        override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
            FileVisitResult.CONTINUE
    })
}
