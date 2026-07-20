package dev.koda.core.engine

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolCallMetadata
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.fromProcess
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MCP integration: config loading, server connection, and the permission
 * bridge that routes every external tool call through the [ToolGate].
 */

@Serializable
data class McpServerSpec(
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
)

@Serializable
data class McpConfigFile(
    val mcpServers: Map<String, McpServerSpec> = emptyMap(),
)

/** One connected MCP server and its tool registry. */
class McpConnection(
    val name: String,
    val registry: ToolRegistry,
) {
    val toolNames: List<String> get() = registry.tools.map { it.name }
}

object McpConnector {
    private val json = Json { ignoreUnknownKeys = true }
    private const val CONNECT_TIMEOUT_MS = 20_000L

    /** Merge `<kodaHome>/mcp.json` and `<cwd>/.koda/mcp.json` (project wins on name clash). */
    fun loadSpecs(kodaHome: Path, cwd: Path): Map<String, McpServerSpec> {
        val global = readConfig(kodaHome.resolve("mcp.json"))
        val project = readConfig(cwd.resolve(".koda").resolve("mcp.json"))
        return global + project
    }

    private fun readConfig(file: Path): Map<String, McpServerSpec> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(McpConfigFile.serializer(), file.readText()).mcpServers
        }.getOrDefault(emptyMap())
    }

    /** Connects one server; throws on failure (caller decides how to report). */
    suspend fun connect(name: String, spec: McpServerSpec): McpConnection = withTimeout(CONNECT_TIMEOUT_MS) {
        val registry = when {
            spec.command != null -> {
                val process = ProcessBuilder(listOf(spec.command) + spec.args)
                    .apply { environment().putAll(spec.env) }
                    .redirectErrorStream(false)
                    .start()
                McpToolRegistryProvider.fromProcess(process)
            }
            spec.url != null -> McpToolRegistryProvider.streamableHttp { url = spec.url }
            else -> error("MCP server '$name' needs either a command or a url")
        }
        McpConnection(name, registry)
    }
}

/**
 * Wraps any external Koog tool (MCP) so its dispatch goes through the
 * [ToolGate] — permission check, approval handshake, ToolBegin/End events —
 * exactly like a built-in tool. Presents the inner tool's descriptor to the
 * model unchanged; results are flattened to the inner tool's string encoding.
 */
@OptIn(ai.koog.agents.core.tools.annotations.InternalAgentToolsApi::class)
class GatedExternalTool(
    private val inner: ToolBase<*, *>,
    private val gate: ToolGate,
    private val serializer: JSONSerializer = KotlinxSerializer(),
) : ToolBase<JSONObject, String>(
    argsType = typeToken<JSONObject>(),
    resultType = typeToken<String>(),
    descriptor = inner.descriptor,
    metadata = inner.metadata,
) {
    override suspend fun execute(args: JSONObject, metadata: ToolCallMetadata): String =
        gate.runExternal(name, args.toString()) {
            val result = inner.executeUnsafe(args, metadata)
            inner.encodeResultToStringUnsafe(result, serializer)
        }

    override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result
}

/** Per-session view of shared MCP connections, gated by the session's [ToolGate]. */
fun gatedMcpRegistry(connections: List<McpConnection>, gate: ToolGate): ToolRegistry {
    val wrapped = connections.flatMap { connection ->
        connection.registry.tools.map { tool -> GatedExternalTool(tool, gate) }
    }
    return ToolRegistry { tools(wrapped) }
}
