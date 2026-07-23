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
import dev.koda.tools.MemoryStore
import dev.koda.tools.MemoryTool
import dev.koda.tools.ReadTool
import dev.koda.tools.SkillCreateTool
import dev.koda.tools.SkillTool
import dev.koda.tools.SkillWriter
import dev.koda.tools.TodoTool
import dev.koda.tools.WebFetchTool
import dev.koda.tools.WebSearchTool
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

class KoogBashTool(gate: ToolGate, executor: dev.koda.tools.ShellExecutor) :
    GatedTool<BashArgs>(typeToken<BashArgs>(), BashTool(executor), gate) {
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
data class SkillArgs(val name: String)

class KoogSkillTool(gate: ToolGate) : GatedTool<SkillArgs>(typeToken<SkillArgs>(), SkillTool(), gate) {
    override fun toJson(args: SkillArgs): JsonObject = buildJsonObject {
        put("name", args.name)
    }
}

@Serializable
data class MemoryArgs(val content: String, val scope: String? = null, val action: String? = null)

class KoogMemoryTool(gate: ToolGate, store: MemoryStore) :
    GatedTool<MemoryArgs>(typeToken<MemoryArgs>(), MemoryTool(store), gate) {
    override fun toJson(args: MemoryArgs): JsonObject = buildJsonObject {
        put("content", args.content)
        args.scope?.let { put("scope", it) }
        args.action?.let { put("action", it) }
    }
}

@Serializable
data class SkillCreateArgs(val name: String, val description: String, val body: String)

class KoogSkillCreateTool(gate: ToolGate, writer: SkillWriter) :
    GatedTool<SkillCreateArgs>(typeToken<SkillCreateArgs>(), SkillCreateTool(writer), gate) {
    override fun toJson(args: SkillCreateArgs): JsonObject = buildJsonObject {
        put("name", args.name); put("description", args.description); put("body", args.body)
    }
}

@Serializable
data class TodoArgs(val items: List<String>)

class KoogTodoTool(gate: ToolGate) : GatedTool<TodoArgs>(typeToken<TodoArgs>(), TodoTool(), gate) {
    override fun toJson(args: TodoArgs): JsonObject = buildJsonObject {
        putJsonArray("items") { args.items.forEach { add(it) } }
    }
}

@Serializable
data class WebFetchArgs(val url: String)

class KoogWebFetchTool(gate: ToolGate) : GatedTool<WebFetchArgs>(typeToken<WebFetchArgs>(), WebFetchTool(), gate) {
    override fun toJson(args: WebFetchArgs): JsonObject = buildJsonObject { put("url", args.url) }
}

@Serializable
data class WebSearchArgs(val query: String)

class KoogWebSearchTool(gate: ToolGate) : GatedTool<WebSearchArgs>(typeToken<WebSearchArgs>(), WebSearchTool(), gate) {
    override fun toJson(args: WebSearchArgs): JsonObject = buildJsonObject { put("query", args.query) }
}

/** The default Koda toolset as a per-session Koog registry. */
fun kodaToolRegistry(
    gate: ToolGate,
    shell: dev.koda.tools.ShellExecutor,
    memory: MemoryStore,
    skillWriter: SkillWriter,
): ToolRegistry = ToolRegistry {
    tools(
        listOf(
            KoogReadTool(gate),
            KoogWriteTool(gate),
            KoogEditTool(gate),
            KoogBashTool(gate, shell),
            KoogGrepTool(gate),
            KoogGlobTool(gate),
            KoogTodoTool(gate),
            KoogSkillTool(gate),
            KoogMemoryTool(gate, memory),
            KoogSkillCreateTool(gate, skillWriter),
            KoogWebFetchTool(gate),
            KoogWebSearchTool(gate),
        )
    )
}

/**
 * Subagent toolset. With [names] null, the default read-only exploration set
 * (read, grep, glob, skill). Otherwise the named subset of the built-in tools
 * — mutating tools are allowed but still pass through the session [ToolGate]
 * (so approvals apply), and `delegate` is never included, so subagents cannot
 * recurse. Unknown names are ignored.
 */
fun kodaScopedRegistry(gate: ToolGate, shell: dev.koda.tools.ShellExecutor, names: List<String>? = null): ToolRegistry {
    val available = mapOf(
        "read" to { KoogReadTool(gate) },
        "write" to { KoogWriteTool(gate) },
        "edit" to { KoogEditTool(gate) },
        "bash" to { KoogBashTool(gate, shell) },
        "grep" to { KoogGrepTool(gate) },
        "glob" to { KoogGlobTool(gate) },
        "todo" to { KoogTodoTool(gate) },
        "skill" to { KoogSkillTool(gate) },
        "web_fetch" to { KoogWebFetchTool(gate) },
        "web_search" to { KoogWebSearchTool(gate) },
    )
    val selected = names?.map { it.lowercase() }?.filter { it in available }?.distinct()
        ?.takeIf { it.isNotEmpty() }
        ?: listOf("read", "grep", "glob", "skill", "web_fetch", "web_search")
    return ToolRegistry { tools(selected.map { available.getValue(it)() }) }
}
