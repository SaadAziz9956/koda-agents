package dev.koda.gateway

import dev.koda.core.KodaCore
import dev.koda.core.Sanitizer
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStopReason
import dev.koda.protocol.UserTurn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The WhatsApp surface's brain. It is a plain client of [KodaCore] over the
 * protocol — no privileged access — mirroring how the CLI and TUI drive turns,
 * but shaped for an asynchronous chat channel:
 *
 *  - one Koda session per contact (`wa-<wa_id>`, deterministic so history
 *    survives restarts),
 *  - a single collector on the core event stream that accumulates each turn's
 *    assistant text and flushes it as one WhatsApp message on turn completion,
 *  - tool approvals surfaced as chat prompts the contact answers with yes/no
 *    (the honest permission gate, adapted to a remote surface — never a silent
 *    auto-approve).
 *
 * Trust boundary: [allowed] gates which wa_ids may drive the agent at all, and
 * every inbound message is run through [Sanitizer] before it reaches the core.
 */
class WhatsAppGateway(
    private val core: KodaCore,
    private val client: WhatsAppClient,
    private val allowed: Set<String>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = ::println,
) {
    private val sessionForContact = ConcurrentHashMap<String, String>()
    private val contactForSession = ConcurrentHashMap<String, String>()
    private val replyBuffers = ConcurrentHashMap<String, StringBuilder>()
    private val pendingApproval = ConcurrentHashMap<String, ApprovalRequest>()

    /** Begin routing core events back to WhatsApp contacts. */
    fun start() {
        scope.launch {
            core.events.collect { event ->
                when (event) {
                    is TextDelta ->
                        replyBuffers.getOrPut(event.sessionId) { StringBuilder() }.append(event.text)

                    // Full text already accumulated from deltas; ignore to avoid duplication.
                    is AssistantMessage -> {}

                    is ApprovalRequest -> onApprovalRequest(event)

                    is ErrorEvent -> contactForSession[event.sessionId]?.let {
                        send(it, "⚠ error: ${event.message}")
                    }

                    is TurnCompleted -> onTurnCompleted(event.sessionId, event.stopReason)

                    else -> {}
                }
            }
        }
    }

    /**
     * Handle one inbound WhatsApp text. Returns silently for non-allowlisted
     * senders (the message is dropped, never fed to the agent).
     */
    fun onInboundText(waId: String, text: String) {
        if (waId !in allowed) {
            log("whatsapp: dropped message from non-allowlisted $waId")
            return
        }
        val sessionId = sessionForContact.getOrPut(waId) { "wa-$waId" }
        contactForSession[sessionId] = waId

        // A pending approval turns the next message into a yes/no answer.
        val approval = pendingApproval.remove(sessionId)
        if (approval != null) {
            val decision = parseDecision(text)
            log("whatsapp: approval ${approval.toolName} for $waId → $decision")
            scope.launch {
                core.submit(ApprovalResponse(newId(), sessionId, approval.approvalId, decision))
            }
            return
        }

        val clean = Sanitizer.sanitizeUntrusted(text, label = "whatsapp:$waId")
        replyBuffers.remove(sessionId) // fresh turn
        scope.launch { core.submit(UserTurn(newId(), sessionId, clean)) }
    }

    private fun onApprovalRequest(event: ApprovalRequest) {
        val waId = contactForSession[event.sessionId] ?: return
        pendingApproval[event.sessionId] = event
        // Flush whatever text preceded the approval so context isn't lost.
        flush(event.sessionId, waId)
        send(
            waId,
            "⚠ Approval needed: ${event.summary}\n\nReply *yes* to allow, *always* to allow this tool from now on, or *no* to deny.",
        )
    }

    private fun onTurnCompleted(sessionId: String, reason: TurnStopReason) {
        val waId = contactForSession[sessionId] ?: return
        // If we're waiting on an approval answer, the turn isn't really over.
        if (pendingApproval.containsKey(sessionId)) return
        flush(sessionId, waId)
        when (reason) {
            TurnStopReason.MAX_ITERATIONS -> send(waId, "_(stopped: hit the step limit for this turn)_")
            TurnStopReason.INTERRUPTED -> send(waId, "_(interrupted)_")
            TurnStopReason.ERROR -> {} // ErrorEvent already reported
            TurnStopReason.COMPLETED -> {}
        }
    }

    /** Send and clear the accumulated reply text for a session, if any. */
    private fun flush(sessionId: String, waId: String) {
        val buffered = replyBuffers.remove(sessionId)?.toString()?.trim().orEmpty()
        if (buffered.isNotEmpty()) send(waId, buffered)
    }

    private fun send(waId: String, body: String) {
        scope.launch(Dispatchers.IO) { client.sendText(waId, body) }
    }

    private fun parseDecision(text: String): ApprovalDecision =
        when (text.trim().lowercase()) {
            "y", "yes", "ok", "okay", "allow", "approve", "sure" -> ApprovalDecision.APPROVE
            "a", "always", "allow always", "always allow" -> ApprovalDecision.APPROVE_ALWAYS
            else -> ApprovalDecision.DENY
        }

    private fun newId() = UUID.randomUUID().toString()
}
