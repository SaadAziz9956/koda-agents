package dev.koda.core.port

import ai.koog.prompt.Prompt

/**
 * Port for session conversation persistence. The engine saves and restores
 * the full Koog [Prompt] (system + history); where it lives (file, SQLite,
 * remote) is an adapter concern chosen at the composition root.
 */
interface SessionRepository {
    fun save(sessionId: String, prompt: Prompt)
    fun load(sessionId: String): Prompt?
}
