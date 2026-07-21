package dev.koda.core.engine

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import dev.koda.protocol.Notice
import dev.koda.tools.MemoryScope
import dev.koda.tools.MemoryStore

/**
 * The background half of the learning loop: after a turn, a separate LLM call
 * reviews the exchange and records genuinely durable facts to memory — so the
 * agent learns without the user (or the model mid-turn) having to ask. Runs
 * off the turn path; never blocks the next user message.
 */
class MemoryReviewer(
    private val executor: PromptExecutor,
    private val model: LLModel,
) {
    private companion object {
        const val SYSTEM = """
You are Koda's memory reviewer. Given a single user/assistant exchange and the
facts already remembered, extract ONLY new, durable, reusable facts worth
keeping for future sessions — lasting user preferences/identity, or stable
facts about the project. Ignore anything transient, task-specific, or already
known.

Output one fact per line, each prefixed with "USER:" (about the user) or
"PROJECT:" (about the codebase). If there is nothing worth saving, output
exactly "NONE". Be extremely selective — most exchanges yield NONE.
"""
    }

    suspend fun reviewAndSave(
        sessionId: String,
        userText: String,
        assistantText: String,
        store: MemoryStore,
        emit: suspend (Notice) -> Unit,
    ) {
        if (userText.isBlank() || assistantText.isBlank()) return
        val existing = (store.load(MemoryScope.USER) + "\n" + store.load(MemoryScope.PROJECT)).trim()
        val input = buildString {
            appendLine("## Already remembered")
            appendLine(existing.ifEmpty { "(nothing yet)" })
            appendLine()
            appendLine("## Exchange")
            appendLine("User: $userText")
            appendLine("Assistant: ${assistantText.take(4000)}")
        }

        val output = runCatching {
            val strategy = functionalStrategy<String, String>("koda-memory-review") { msg ->
                val response = requestLLMWithoutTools(msg)
                response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            }
            AIAgent(
                promptExecutor = executor,
                llmModel = model,
                strategy = strategy,
                systemPrompt = SYSTEM.trimIndent(),
                maxIterations = 3,
            ).run(input)
        }.getOrNull() ?: return

        for (line in output.lines()) {
            val trimmed = line.trim()
            val (scope, fact) = when {
                trimmed.startsWith("USER:", true) -> MemoryScope.USER to trimmed.removePrefix("USER:").removePrefix("user:").trim()
                trimmed.startsWith("PROJECT:", true) -> MemoryScope.PROJECT to trimmed.removePrefix("PROJECT:").removePrefix("project:").trim()
                else -> continue
            }
            if (fact.isNotEmpty() && !fact.equals("none", true)) {
                store.append(scope, fact)
                emit(Notice(sessionId, "🧠 remembered (${scope.name.lowercase()}): ${fact.take(80)}"))
            }
        }
    }
}
