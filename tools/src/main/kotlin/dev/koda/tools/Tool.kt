package dev.koda.tools

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Koda tool: schema + handler. Handlers return plain strings — what the
 * model sees. Tools never talk to the user or the UI; approval and rendering
 * happen in the core, in front of dispatch.
 */
interface KodaTool {
    val name: String
    val description: String
    /** JSON Schema for the arguments object. */
    val parameters: JsonObject

    /** Mutating tools are gated by the permission engine before dispatch. */
    val mutating: Boolean

    /** One-line human summary of a call, shown in approval prompts and logs. */
    fun summarize(args: JsonObject): String

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

/**
 * Notified by mutating file tools just before they change a path, so a
 * checkpoint layer can capture the prior state for undo (see /rewind). The
 * core supplies the implementation; tools stay ignorant of what it does.
 */
fun interface FileSnapshotSink {
    fun capture(path: Path)
}

/** Per-session state shared across tool invocations. */
class ToolContext(
    val cwd: Path,
) {
    /** Files the model has Read this session — required before Edit/Write of existing files. */
    val readFiles: MutableSet<Path> = mutableSetOf()

    /** Set by the core; mutating file tools call it before writing, for checkpoint/undo. */
    var snapshotSink: FileSnapshotSink? = null

    /** Session todo list (see [TodoTool]). */
    val todos: MutableList<TodoItem> = mutableListOf()

    /** Skills available to this session, keyed by name (see [SkillTool]). */
    val skills: MutableMap<String, LoadedSkill> = mutableMapOf()

    /** Subtree directories whose context files have already been surfaced this session. */
    val seenContextDirs: MutableSet<Path> = mutableSetOf()

    fun resolve(filePath: String): Path {
        val p = Path.of(filePath)
        return (if (p.isAbsolute) p else cwd.resolve(p)).normalize()
    }
}

data class TodoItem(val text: String, var done: Boolean = false)

data class ToolResult(
    val output: String,
    val isError: Boolean = false,
) {
    companion object {
        fun error(message: String) = ToolResult(message, isError = true)
    }
}

class ToolRegistry(tools: List<KodaTool>) {
    private val byName: Map<String, KodaTool> = tools.associateBy { it.name }

    val all: Collection<KodaTool> get() = byName.values

    operator fun get(name: String): KodaTool? = byName[name]

    companion object {
        /** The default Koda v0 toolset. */
        fun default(): ToolRegistry = ToolRegistry(
            listOf(
                ReadTool(),
                WriteTool(),
                EditTool(),
                BashTool(),
                GrepTool(),
                GlobTool(),
                TodoTool(),
                SkillTool(),
            )
        )
    }
}

internal fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("missing required parameter: $key")

internal fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.takeIf { it.isString || it.content.isNotEmpty() }?.content

internal fun JsonObject.optionalInt(key: String): Int? =
    this[key]?.jsonPrimitive?.content?.toIntOrNull()

internal fun JsonObject.optionalBoolean(key: String): Boolean =
    this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
