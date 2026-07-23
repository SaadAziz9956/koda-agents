package dev.koda.core

import ai.koog.prompt.Prompt
import dev.koda.core.port.PermissionPolicy
import dev.koda.core.port.SessionRepository
import dev.koda.tools.ToolContext

/**
 * Per-session state: the conversation (as a Koog [Prompt]), tool context,
 * frozen system prompt, permission policy, and approval broker. Pure state —
 * no orchestration. The prompt is only committed at the end of a successful
 * turn, so an interrupted turn rolls back cleanly.
 */
class AgentSession(
    val id: String,
    val systemPrompt: String,
    val toolContext: ToolContext,
    val permissions: PermissionPolicy,
    val approvals: ApprovalBroker,
    val clarifications: ClarifyBroker = ClarifyBroker(),
    private val repository: SessionRepository,
) {
    /** Full conversation including system message; null until the first completed turn. */
    var prompt: Prompt? = repository.load(id)

    /** Set by the engine at turn start; read by the tool gate for event correlation. */
    @Volatile
    var currentTurnId: String = ""

    /** Runtime model override for this session (see SetModel); null = the core default. */
    @Volatile
    var modelOverride: ai.koog.prompt.llm.LLModel? = null

    fun persist() {
        prompt?.let { repository.save(id, it) }
    }
}
