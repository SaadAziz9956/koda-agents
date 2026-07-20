package dev.koda.core.engine

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import dev.koda.core.AgentSession
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStarted
import dev.koda.protocol.TurnStopReason
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The Koog-backed turn engine: one user turn from submission to completion,
 * run as a Koog functional-strategy agent. The strategy restores the
 * session's saved conversation, then loops model -> tools -> model until a
 * text-only response or the iteration budget. Tool permission gating lives
 * in the bridged tools ([ToolGate]), so the loop stays framework-pure.
 *
 * The session prompt is committed only after a successful turn — an
 * interrupted or failed turn rolls back to the pre-turn conversation.
 */
class KoogEngine(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val events: EventSink,
    private val maxIterationsPerTurn: Int,
) {
    suspend fun runTurn(session: AgentSession, registry: ToolRegistry, text: String) {
        val turnId = UUID.randomUUID().toString()
        session.currentTurnId = turnId
        events.emit(TurnStarted(session.id, turnId))

        var stopReason = TurnStopReason.COMPLETED
        try {
            val strategy = functionalStrategy<String, String>("koda-turn") { input ->
                // Restore the session's conversation (system + history) if it exists;
                // otherwise the agent config's system prompt applies to a fresh one.
                session.prompt?.let { saved ->
                    llm.writeSession { prompt = saved }
                }

                var response = requestLLM(input)
                emitAssistant(session, turnId, response)

                var iterations = 0
                var calls = getToolCalls(response)
                while (calls.isNotEmpty()) {
                    if (++iterations > maxIterationsPerTurn) {
                        // Budget hit: run the pending calls, then force a wrap-up
                        // without tools so the prompt ends in a valid state.
                        val results = executeTools(calls)
                        response = sendToolResultsWithoutTools(results)
                        emitAssistant(session, turnId, response)
                        stopReason = TurnStopReason.MAX_ITERATIONS
                        break
                    }
                    val results = executeTools(calls)
                    response = sendToolResults(results)
                    emitAssistant(session, turnId, response)
                    calls = getToolCalls(response)
                }

                // Commit the turn: the session conversation becomes the new baseline.
                session.prompt = llm.readSession { prompt }
                response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            }

            val agent = AIAgent(
                promptExecutor = executor,
                llmModel = model,
                strategy = strategy,
                toolRegistry = registry,
                systemPrompt = session.systemPrompt,
                maxIterations = maxIterationsPerTurn * 3 + 8,
            )

            agent.run(text)
            session.persist()
        } catch (e: CancellationException) {
            stopReason = TurnStopReason.INTERRUPTED
        } catch (e: Exception) {
            stopReason = TurnStopReason.ERROR
            withContext(NonCancellable) {
                events.emit(ErrorEvent(session.id, e.message ?: e.toString()))
            }
        }

        withContext(NonCancellable) {
            events.emit(TurnCompleted(session.id, turnId, stopReason))
        }
    }

    private suspend fun AIAgentFunctionalContext.emitAssistant(
        session: AgentSession,
        turnId: String,
        response: Message.Assistant,
    ) {
        val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        if (text.isNotEmpty()) {
            events.emit(TextDelta(session.id, turnId, text))
            events.emit(AssistantMessage(session.id, turnId, text))
        }
        events.emit(TokenUsage(session.id, latestTokenUsage().toLong(), 0))
    }
}
