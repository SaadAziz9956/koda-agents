package dev.koda.core

import dev.koda.core.port.PermissionPolicy
import dev.koda.core.port.SessionRepository
import dev.koda.providers.ChatMessage
import dev.koda.tools.ToolContext

/**
 * Per-session state: the conversation, tool context, frozen system prompt,
 * permission policy, and approval broker. Pure state — no orchestration.
 * The message list is only mutated through [addMessage] so persistence
 * can never drift from memory.
 */
class AgentSession(
    val id: String,
    val systemPrompt: String,
    val toolContext: ToolContext,
    val permissions: PermissionPolicy,
    val approvals: ApprovalBroker,
    private val repository: SessionRepository,
) {
    private val _messages: MutableList<ChatMessage> = repository.load(id).toMutableList()

    val messages: List<ChatMessage> get() = _messages

    fun addMessage(message: ChatMessage) {
        _messages += message
        repository.append(id, message)
    }
}
