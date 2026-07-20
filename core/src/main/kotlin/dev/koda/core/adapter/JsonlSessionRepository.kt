package dev.koda.core.adapter

import dev.koda.core.port.SessionRepository
import dev.koda.providers.ChatMessage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlinx.serialization.json.Json

/**
 * Append-only JSONL transcript per session: one normalized [ChatMessage]
 * per line under `<sessionsDir>/<sessionId>.jsonl`.
 */
class JsonlSessionRepository(private val sessionsDir: Path) : SessionRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    init {
        sessionsDir.createDirectories()
    }

    private fun fileFor(sessionId: String): Path = sessionsDir.resolve("$sessionId.jsonl")

    override fun append(sessionId: String, message: ChatMessage) {
        Files.writeString(
            fileFor(sessionId),
            json.encodeToString(ChatMessage.serializer(), message) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    override fun load(sessionId: String): List<ChatMessage> {
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString(ChatMessage.serializer(), line) }.getOrNull()
            }
    }
}
