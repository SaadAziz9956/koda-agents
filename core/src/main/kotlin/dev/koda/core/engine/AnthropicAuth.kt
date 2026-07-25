package dev.koda.core.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Anthropic authentication for Koda — a bring-your-own API key, set from any
 * surface at runtime. The key lives in a mutable [ApiKeyHolder] that a Ktor
 * request plugin stamps onto every LLM call, so a new key takes effect
 * immediately without rebuilding the executor. Persisted at
 * ~/.koda/credentials.json (0600), which is inside the sandbox's protected set.
 *
 * NOTE: this is deliberately API-key only. Subscription (Pro/Max) inference in
 * a third-party app requires impersonating Anthropic's official client, which
 * Koda does not do.
 */

/** Thread-safe holder for the active API key; read per request by [apiKeyPlugin]. */
class ApiKeyHolder(@Volatile var key: String? = null)

/** Ktor plugin that overwrites `x-api-key` with the holder's current key on
 *  every request (the LLM client is built with a placeholder). */
fun apiKeyPlugin(holder: ApiKeyHolder) = createClientPlugin("koda-api-key") {
    onRequest { request, _ ->
        val k = holder.key
        if (!k.isNullOrBlank()) {
            request.headers.remove("x-api-key")
            request.headers.append("x-api-key", k)
        }
    }
}

@Serializable
private data class Credentials(val anthropicKey: String? = null)

/** Reads/writes the key at ~/.koda/credentials.json with owner-only perms. */
class AuthStore(private val kodaHome: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: Path get() = kodaHome.resolve("credentials.json")

    fun loadAnthropicKey(): String? = runCatching {
        if (!Files.exists(file)) return null
        json.decodeFromString(Credentials.serializer(), Files.readString(file)).anthropicKey?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun saveAnthropicKey(key: String) {
        Files.createDirectories(kodaHome)
        Files.writeString(file, json.encodeToString(Credentials.serializer(), Credentials(key)))
        // Owner read/write only; ignored on filesystems without POSIX perms.
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    fun clear() {
        runCatching { Files.writeString(file, json.encodeToString(Credentials.serializer(), Credentials(null))) }
    }
}

/** Result of a key check: [ok] plus a short display [label] or an [error]. */
data class KeyCheck(val ok: Boolean, val label: String = "", val error: String? = null)

/**
 * Validate an Anthropic key with a cheap authenticated GET /v1/models. Returns
 * a masked label on success (never echoes the key) or a human error otherwise.
 */
suspend fun validateAnthropicKey(key: String): KeyCheck {
    val masked = "key ••••" + key.takeLast(4)
    val client = HttpClient(CIO)
    return try {
        val resp: HttpResponse = client.get("https://api.anthropic.com/v1/models") {
            header("x-api-key", key)
            header("anthropic-version", "2023-06-01")
        }
        when (resp.status.value) {
            in 200..299 -> KeyCheck(true, "Anthropic · $masked")
            401, 403 -> KeyCheck(false, error = "Key rejected (${resp.status.value}). Check the key and try again.")
            else -> KeyCheck(false, error = "Anthropic returned HTTP ${resp.status.value}.")
        }
    } catch (e: Exception) {
        KeyCheck(false, error = "Couldn't reach Anthropic: ${e.message ?: "network error"}")
    } finally {
        client.close()
    }
}
