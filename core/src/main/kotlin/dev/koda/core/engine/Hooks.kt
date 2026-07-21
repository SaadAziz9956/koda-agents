package dev.koda.core.engine

import dev.koda.protocol.Notice
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Lifecycle hooks: user-configured subprocesses fired at defined points, the
 * harness's escape hatch for automation and policy. A hook receives a JSON
 * event on stdin and influences flow via exit code and stdout JSON.
 *
 * Protocol (per hook process):
 * - stdin: `{event, sessionId, cwd, tool?, args?, output?, isError?, text?}`
 * - exit 0 + no stdout → proceed
 * - exit 2 → deny/block (stderr becomes the reason)
 * - stdout `{"decision":"deny","reason":"…"}` → deny/block
 * - stdout `{"decision":"allow"}` → PreToolUse only: bypass the permission gate
 * - stdout `{"input":{…}}` → PreToolUse only (built-in tools): replace args
 */

enum class HookEvent { SessionStart, UserPromptSubmit, PreToolUse, PostToolUse, Stop }

@Serializable
data class HookSpec(
    /** Glob on tool name for Pre/PostToolUse ("*" = all); ignored for other events. */
    val matcher: String = "*",
    val command: String,
    val timeoutMs: Long = 30_000,
)

@Serializable
private data class HookConfigFile(
    val hooks: Map<String, List<HookSpec>> = emptyMap(),
)

sealed interface PreToolDecision {
    data object Proceed : PreToolDecision
    data object Allow : PreToolDecision
    data class Deny(val reason: String) : PreToolDecision
    data class Rewrite(val args: JsonObject) : PreToolDecision
}

class HookRunner(
    private val hooks: Map<HookEvent, List<HookSpec>>,
    private val events: EventSink,
) {
    val isEmpty: Boolean get() = hooks.values.all { it.isEmpty() }

    suspend fun fireSessionStart(sessionId: String, cwd: Path) {
        runObservers(HookEvent.SessionStart, sessionId, cwd, buildJsonObject {})
    }

    suspend fun fireStop(sessionId: String, cwd: Path) {
        runObservers(HookEvent.Stop, sessionId, cwd, buildJsonObject {})
    }

    /** Returns false if a hook blocked the prompt. */
    suspend fun fireUserPromptSubmit(sessionId: String, cwd: Path, text: String): Boolean {
        val payload = buildJsonObject { put("text", text) }
        for (spec in specsFor(HookEvent.UserPromptSubmit)) {
            val r = execute(spec, HookEvent.UserPromptSubmit, sessionId, cwd, payload)
            if (r.blocked) {
                events.emit(Notice(sessionId, "prompt blocked by hook: ${r.reason ?: "no reason"}"))
                return false
            }
        }
        return true
    }

    suspend fun firePreToolUse(
        sessionId: String,
        cwd: Path,
        tool: String,
        args: JsonObject,
    ): PreToolDecision {
        val payload = buildJsonObject { put("tool", tool); put("args", args) }
        var decision: PreToolDecision = PreToolDecision.Proceed
        for (spec in specsFor(HookEvent.PreToolUse).filter { matches(it.matcher, tool) }) {
            val r = execute(spec, HookEvent.PreToolUse, sessionId, cwd, payload)
            when {
                r.blocked -> return PreToolDecision.Deny(r.reason ?: "blocked by hook")
                r.rewrite != null -> decision = PreToolDecision.Rewrite(r.rewrite)
                r.allow -> if (decision == PreToolDecision.Proceed) decision = PreToolDecision.Allow
            }
        }
        return decision
    }

    suspend fun firePostToolUse(
        sessionId: String,
        cwd: Path,
        tool: String,
        args: JsonObject,
        output: String,
        isError: Boolean,
    ) {
        val payload = buildJsonObject {
            put("tool", tool); put("args", args); put("output", output); put("isError", isError)
        }
        specsFor(HookEvent.PostToolUse).filter { matches(it.matcher, tool) }
            .forEach { execute(it, HookEvent.PostToolUse, sessionId, cwd, payload) }
    }

    private suspend fun runObservers(event: HookEvent, sessionId: String, cwd: Path, payload: JsonObject) {
        specsFor(event).forEach { execute(it, event, sessionId, cwd, payload) }
    }

    private fun specsFor(event: HookEvent): List<HookSpec> = hooks[event].orEmpty()

    private data class HookResult(
        val blocked: Boolean = false,
        val allow: Boolean = false,
        val rewrite: JsonObject? = null,
        val reason: String? = null,
    )

    private suspend fun execute(
        spec: HookSpec,
        event: HookEvent,
        sessionId: String,
        cwd: Path,
        extra: JsonObject,
    ): HookResult = withContext(Dispatchers.IO) {
        val stdin = buildJsonObject {
            put("event", event.name)
            put("sessionId", sessionId)
            put("cwd", cwd.toString())
            extra.forEach { (k, v) -> put(k, v) }
        }.toString()

        try {
            val process = ProcessBuilder("/bin/sh", "-c", spec.command)
                .directory(cwd.toFile())
                .start()
            process.outputStream.bufferedWriter().use { it.write(stdin) }
            val finished = process.waitFor(spec.timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                events.emit(Notice(sessionId, "hook timed out: ${spec.command.take(60)}"))
                return@withContext HookResult()
            }
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exit = process.exitValue()

            if (exit == 2) return@withContext HookResult(blocked = true, reason = stderr.ifEmpty { null })

            if (stdout.startsWith("{")) {
                val json = runCatching { Json.parseToJsonElement(stdout).jsonObject }.getOrNull()
                if (json != null) {
                    val decision = json["decision"]?.jsonPrimitive?.content
                    val reason = json["reason"]?.jsonPrimitive?.content
                    val input = json["input"]?.let { it as? JsonObject }
                    return@withContext when {
                        decision == "deny" || decision == "block" -> HookResult(blocked = true, reason = reason)
                        input != null -> HookResult(rewrite = input)
                        decision == "allow" -> HookResult(allow = true)
                        else -> HookResult()
                    }
                }
            }
            HookResult()
        } catch (e: Exception) {
            events.emit(Notice(sessionId, "hook failed (${spec.command.take(40)}): ${e.message ?: e}"))
            HookResult()
        }
    }

    /** Tiny glob: `*` matches any run of chars; everything else literal. */
    private fun matches(pattern: String, value: String): Boolean {
        if (pattern == "*" || pattern == value) return true
        val regex = "^" + Regex.escape(pattern).replace("\\*", "\\E.*\\Q") + "$"
        return runCatching { Regex(regex).matches(value) }.getOrDefault(false)
    }
}

object HookLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Merge global `<kodaHome>/hooks.json` and project `<cwd>/.koda/hooks.json`. */
    fun load(kodaHome: Path, cwd: Path, events: EventSink): HookRunner {
        val merged = LinkedHashMap<HookEvent, MutableList<HookSpec>>()
        for (file in listOf(kodaHome.resolve("hooks.json"), cwd.resolve(".koda").resolve("hooks.json"))) {
            readConfig(file).forEach { (name, specs) ->
                val event = runCatching { HookEvent.valueOf(name) }.getOrNull() ?: return@forEach
                merged.getOrPut(event) { mutableListOf() }.addAll(specs)
            }
        }
        return HookRunner(merged, events)
    }

    private fun readConfig(file: Path): Map<String, List<HookSpec>> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(HookConfigFile.serializer(), file.readText()).hooks
        }.getOrDefault(emptyMap())
    }
}
