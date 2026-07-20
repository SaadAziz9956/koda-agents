package dev.koda.core.adapter

import ai.koog.prompt.Prompt
import dev.koda.core.port.SessionRepository
import dev.koda.core.port.StoredSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

/**
 * One JSON file per session holding the serialized Koog [Prompt]
 * under `<sessionsDir>/<sessionId>.json`.
 */
class PromptFileSessionRepository(private val sessionsDir: Path) : SessionRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    init {
        sessionsDir.createDirectories()
    }

    private fun fileFor(sessionId: String): Path = sessionsDir.resolve("$sessionId.json")

    override fun save(sessionId: String, prompt: Prompt) {
        fileFor(sessionId).writeText(json.encodeToString(Prompt.serializer(), prompt))
    }

    override fun load(sessionId: String): Prompt? {
        val file = fileFor(sessionId)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(Prompt.serializer(), file.readText())
        }.getOrNull()
    }

    override fun list(): List<StoredSession> =
        sessionsDir.listDirectoryEntries("*.json")
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".json")
                val updated = runCatching {
                    Files.getLastModifiedTime(file).toMillis()
                }.getOrDefault(0L)
                val messages = load(id)?.messages?.size ?: return@mapNotNull null
                StoredSession(id, updated, messages)
            }
            .sortedByDescending { it.updatedAtEpochMs }
}
