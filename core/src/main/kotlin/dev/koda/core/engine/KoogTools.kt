package dev.koda.core.engine

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.TypeToken
import ai.koog.serialization.typeToken
import dev.koda.tools.BashTool
import dev.koda.tools.EditTool
import dev.koda.tools.GlobTool
import dev.koda.tools.GrepTool
import dev.koda.tools.KodaTool
import dev.koda.tools.ReadTool
import dev.koda.tools.TodoTool
import dev.koda.tools.WriteTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Koog-facing tool bridge. Each tool is a Koog [SimpleTool] with typed,
 * schema-generated args whose execution goes through the [ToolGate]
 * (permissions, approval, events) into the framework-free domain tools
 * in the `tools` module. Field names are snake_case on purpose — they are
 * the wire schema the model sees.
 */
abstract class GatedTool<TArgs>(
    argsType: TypeToken,
    private val domain: KodaTool,
    private val gate: ToolGate,
) : SimpleTool<TArgs>(argsType, domain.name, domain.description) {

    protected abstract fun toJson(args: TArgs): JsonObject

    final override suspend fun execute(args: TArgs): String = gate.run(domain, toJson(args))
}

@Serializable
data class ReadArgs(val file_path: String, val offset: Int? = null, val limit: Int? = null)

class KoogReadTool(gate: ToolGate) : GatedTool<ReadArgs>(typeToken<ReadArgs>(), ReadTool(), gate) {
    override fun toJson(args: ReadArgs): JsonObject = buildJsonObject {
        put("file_path", args.file_path)
        args.offset?.let { put("offset", it) }
        args.limit?.let { put("limit", it) }
    }
}

@Serializable
data class WriteArgs(val file_path: String, val content: String)

class KoogWriteTool(gate: ToolGate) : GatedTool<WriteArgs>(typeToken<WriteArgs>(), WriteTool(), gate) {
    override fun toJson(args: WriteArgs): JsonObject = buildJsonObject {
        put("file_path", args.file_path)
        put("content", args.content)
    }
}

@Serializable
data class EditArgs(
    val file_path: String,
    val old_string: String,
    val new_string: String,
    val replace_all: Boolean? = null,
)

class KoogEditTool(gate: ToolGate) : GatedTool<EditArgs>(typeToken<EditArgs>(), EditTool(), gate) {
    override fun toJson(args: EditArgs): JsonObject = buildJsonObject {
        put("file_path", args.file_path)
        put("old_string", args.old_string)
        put("new_string", args.new_string)
        args.replace_all?.let { put("replace_all", it) }
    }
}

@Serializable
data class BashArgs(val command: String, val timeout: Int? = null)

class KoogBashTool(gate: ToolGate) : GatedTool<BashArgs>(typeToken<BashArgs>(), BashTool(), gate) {
    override fun toJson(args: BashArgs): JsonObject = buildJsonObject {
        put("command", args.command)
        args.timeout?.let { put("timeout", it) }
    }
}

@Serializable
data class GrepArgs(val pattern: String, val path: String? = null, val glob: String? = null)

class KoogGrepTool(gate: ToolGate) : GatedTool<GrepArgs>(typeToken<GrepArgs>(), GrepTool(), gate) {
    override fun toJson(args: GrepArgs): JsonObject = buildJsonObject {
        put("pattern", args.pattern)
        args.path?.let { put("path", it) }
        args.glob?.let { put("glob", it) }
    }
}

@Serializable
data class GlobArgs(val pattern: String, val path: String? = null)

class KoogGlobTool(gate: ToolGate) : GatedTool<GlobArgs>(typeToken<GlobArgs>(), GlobTool(), gate) {
    override fun toJson(args: GlobArgs): JsonObject = buildJsonObject {
        put("pattern", args.pattern)
        args.path?.let { put("path", it) }
    }
}

@Serializable
data class TodoArgs(val items: List<String>)

class KoogTodoTool(gate: ToolGate) : GatedTool<TodoArgs>(typeToken<TodoArgs>(), TodoTool(), gate) {
    override fun toJson(args: TodoArgs): JsonObject = buildJsonObject {
        putJsonArray("items") { args.items.forEach { add(it) } }
    }
}

/** The default Koda toolset as a per-session Koog registry. */
fun kodaToolRegistry(gate: ToolGate): ToolRegistry = ToolRegistry {
    tools(
        listOf(
            KoogReadTool(gate),
            KoogWriteTool(gate),
            KoogEditTool(gate),
            KoogBashTool(gate),
            KoogGrepTool(gate),
            KoogGlobTool(gate),
            KoogTodoTool(gate),
        )
    )
}
