package dev.koda.core.port

import dev.koda.providers.ChatMessage

/**
 * Port for session transcript persistence. The core appends and loads
 * normalized messages; where they live (JSONL, SQLite, remote) is an
 * adapter concern chosen at the composition root.
 */
interface SessionRepository {
    fun append(sessionId: String, message: ChatMessage)
    fun load(sessionId: String): List<ChatMessage>
}
