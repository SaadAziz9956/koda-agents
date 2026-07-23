package dev.koda.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Brokers the agent's clarifying-question handshake: the `ask` tool parks on
 * [await] while the surface shows a picker, and answers via [resolve]. The
 * answer (a chosen option, free text, or a "let's discuss" note) becomes the
 * tool result the model continues from. One broker per session.
 */
class ClarifyBroker {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    suspend fun await(clarifyId: String): String {
        val deferred = CompletableDeferred<String>()
        pending[clarifyId] = deferred
        return try {
            deferred.await()
        } finally {
            pending.remove(clarifyId)
        }
    }

    fun resolve(clarifyId: String, answer: String) {
        pending.remove(clarifyId)?.complete(answer)
    }

    fun cancelAll() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
