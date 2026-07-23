package dev.koda.core.engine

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.context.AIAgentFunctionalContext
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import dev.koda.core.AgentSession
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.ReasoningDelta
import dev.koda.protocol.SessionCompacted
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStarted
import dev.koda.protocol.TurnStopReason
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Compact automatically once the conversation crosses this share of the context window. */
private const val AUTO_COMPACT_THRESHOLD = 0.8

/**
 * The Koog-backed turn engine: one user turn from submission to completion,
 * run as a Koog functional-strategy agent. Each model call streams token
 * deltas out through the [EventSink]; tool permission gating lives in the
 * bridged tools ([ToolGate]), so the loop stays framework-pure.
 *
 * The session prompt is committed only after a successful turn — an
 * interrupted or failed turn rolls back to the pre-turn conversation.
 */
class KoogEngine(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val events: EventSink,
    private val maxIterationsPerTurn: Int,
    private val hooks: HookRunner,
    /** Fired (non-blocking) after a successful turn with (sessionId, userInput, assistantText). */
    private val onTurnCompleted: (suspend (String, String, String) -> Unit)? = null,
) {
    suspend fun runTurn(
        session: AgentSession,
        registry: ToolRegistry,
        text: String,
        attachments: List<String> = emptyList(),
    ) {
        val turnId = UUID.randomUUID().toString()
        session.currentTurnId = turnId
        events.emit(TurnStarted(session.id, turnId))

        if (!hooks.fireUserPromptSubmit(session.id, session.toolContext.cwd, text)) {
            events.emit(TurnCompleted(session.id, turnId, TurnStopReason.COMPLETED))
            return
        }

        var stopReason = TurnStopReason.COMPLETED
        try {
            val strategy = functionalStrategy<String, String>("koda-turn") { input ->
                restoreConversation(session)
                autoCompactIfNeeded(session)

                var response = streamIteration(session, turnId) {
                    if (attachments.isEmpty()) {
                        user(input)
                    } else {
                        user {
                            attachments.forEach { a ->
                                if (a.startsWith("data:") || a.startsWith("http://") || a.startsWith("https://")) image(a)
                                else image(kotlinx.io.files.Path(session.toolContext.cwd.resolve(a).toString()))
                            }
                            text(input)
                        }
                    }
                }
                emitIterationEnd(session, turnId, response)

                var iterations = 0
                var calls = getToolCalls(response)
                while (calls.isNotEmpty()) {
                    if (++iterations > maxIterationsPerTurn) {
                        // Budget hit: run the pending calls, then force a wrap-up
                        // without tools so the prompt ends in a valid state.
                        val results = executeTools(calls)
                        response = sendToolResultsWithoutTools(results)
                        emitWholeText(session, turnId, response)
                        emitIterationEnd(session, turnId, response)
                        stopReason = TurnStopReason.MAX_ITERATIONS
                        break
                    }
                    val results = executeTools(calls)
                    response = streamIteration(session, turnId) {
                        user { results.forEach { toolResult(it.toMessagePart()) } }
                    }
                    emitIterationEnd(session, turnId, response)
                    calls = getToolCalls(response)
                }

                // Commit the turn: the session conversation becomes the new baseline.
                session.prompt = llm.readSession { prompt }
                response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
            }

            // The model sees @file mentions expanded with their contents; hooks
            // and memory review keep the user's original text.
            val expanded = expandFileMentions(text, session.toolContext.cwd)
            val finalText = newAgent(session, registry, strategy = strategy).run(expanded)
            session.persist()
            onTurnCompleted?.invoke(session.id, text, finalText) // non-blocking: launches background review
        } catch (e: CancellationException) {
            stopReason = TurnStopReason.INTERRUPTED
        } catch (e: Exception) {
            stopReason = TurnStopReason.ERROR
            withContext(NonCancellable) {
                events.emit(ErrorEvent(session.id, e.message ?: e.toString()))
            }
        }

        withContext(NonCancellable) {
            hooks.fireStop(session.id, session.toolContext.cwd)
            events.emit(TurnCompleted(session.id, turnId, stopReason))
        }
    }

    /** Manual /compact: compress the session history into a summary and commit it. */
    suspend fun compact(session: AgentSession, registry: ToolRegistry) {
        try {
            val strategy = functionalStrategy<String, String>("koda-compact") { _ ->
                restoreConversation(session)
                val before = llm.readSession { prompt.messages.size }
                compressHistory()
                session.prompt = llm.readSession { prompt }
                val after = llm.readSession { prompt.messages.size }
                events.emit(SessionCompacted(session.id, before, after))
                ""
            }
            newAgent(session, registry, strategy = strategy).run("compact")
            session.persist()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            events.emit(ErrorEvent(session.id, "Compaction failed: ${e.message ?: e.toString()}"))
        }
    }

    private fun newAgent(
        session: AgentSession,
        registry: ToolRegistry,
        strategy: ai.koog.agents.core.agent.AIAgentFunctionalStrategy<String, String>,
    ) = AIAgent(
        promptExecutor = executor,
        llmModel = session.modelOverride ?: model,
        strategy = strategy,
        toolRegistry = registry,
        systemPrompt = session.systemPrompt,
        maxIterations = maxIterationsPerTurn * 3 + 8,
    )

    /** Restore the saved conversation (system + history); fresh sessions keep the config prompt. */
    private suspend fun AIAgentFunctionalContext.restoreConversation(session: AgentSession) {
        session.prompt?.let { saved ->
            llm.writeSession { prompt = saved }
        }
    }

    private suspend fun AIAgentFunctionalContext.autoCompactIfNeeded(session: AgentSession) {
        val contextLength = model.contextLength ?: return
        val used = latestTokenUsage().toLong()
        if (used > contextLength * AUTO_COMPACT_THRESHOLD) {
            val before = llm.readSession { prompt.messages.size }
            compressHistory()
            val after = llm.readSession { prompt.messages.size }
            events.emit(SessionCompacted(session.id, before, after))
        }
    }

    /**
     * One model call with token-level streaming: appends [append] to the
     * prompt, streams the response (emitting protocol deltas), rebuilds the
     * assistant message from the frames, and appends it to the prompt —
     * Koog's streaming call does not append automatically.
     */
    private suspend fun AIAgentFunctionalContext.streamIteration(
        session: AgentSession,
        turnId: String,
        append: PromptBuilder.() -> Unit,
    ): Message.Assistant = llm.writeSession {
        appendPrompt(append)
        val frames = mutableListOf<StreamFrame>()
        requestLLMStreaming().collect { frame ->
            frames += frame
            when (frame) {
                is StreamFrame.TextDelta ->
                    events.emit(TextDelta(session.id, turnId, frame.text))
                is StreamFrame.ReasoningDelta ->
                    frame.text?.let { events.emit(ReasoningDelta(session.id, turnId, it)) }
                else -> {}
            }
        }
        val response = frames.toMessageResponse()
        appendPrompt { message(response) }
        response
    }

    /** For non-streamed responses (wrap-up path): emit the text in one delta. */
    private suspend fun emitWholeText(session: AgentSession, turnId: String, response: Message.Assistant) {
        val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        if (text.isNotEmpty()) events.emit(TextDelta(session.id, turnId, text))
    }

    private suspend fun AIAgentFunctionalContext.emitIterationEnd(
        session: AgentSession,
        turnId: String,
        response: Message.Assistant,
    ) {
        val text = response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        if (text.isNotEmpty()) events.emit(AssistantMessage(session.id, turnId, text))
        events.emit(TokenUsage(session.id, latestTokenUsage().toLong(), 0))
    }
}
