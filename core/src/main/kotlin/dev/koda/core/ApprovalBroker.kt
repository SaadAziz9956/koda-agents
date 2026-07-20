package dev.koda.core

import dev.koda.protocol.ApprovalDecision
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Brokers the async approval handshake: the agent loop parks on [await],
 * the surface answers via [resolve]. One broker per session.
 */
class ApprovalBroker {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()

    suspend fun await(approvalId: String): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        pending[approvalId] = deferred
        return try {
            deferred.await()
        } finally {
            pending.remove(approvalId)
        }
    }

    fun resolve(approvalId: String, decision: ApprovalDecision) {
        pending.remove(approvalId)?.complete(decision)
    }

    fun cancelAll() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }
}
