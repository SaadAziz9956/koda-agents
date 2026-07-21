package dev.koda.core.engine

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.MessagePart
import ai.koog.serialization.typeToken
import dev.koda.protocol.Notice
import kotlinx.serialization.Serializable

@Serializable
data class DelegateArgs(val task: String)

/**
 * Spawns a subagent: a fresh Koog agent with its own conversation and a
 * scoped, read-only toolset, run to completion on [DelegateArgs.task]. Only
 * the subagent's final text is returned to the parent — its intermediate
 * reasoning and tool calls never enter the parent conversation, which keeps
 * the parent context small (the Explore-agent pattern).
 *
 * The subagent shares the session's [ToolGate], so its tool calls still emit
 * ToolBegin/End events and honor the permission policy. Its toolset excludes
 * delegate itself, so subagents cannot recurse.
 */
class DelegateTool(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val scopedRegistry: ToolRegistry,
    private val events: EventSink,
    private val sessionId: String,
    private val maxIterations: Int,
) : SimpleTool<DelegateArgs>(
    argsType = typeToken<DelegateArgs>(),
    name = "delegate",
    description =
        "Delegate a focused, read-only investigation to a subagent (e.g. \"find where " +
        "X is configured and summarize how it works\"). The subagent explores on its own " +
        "and returns only its findings. Use it to keep your own context focused when a " +
        "sub-question needs many file reads or searches.",
) {
    override suspend fun execute(args: DelegateArgs): String {
        events.emit(Notice(sessionId, "⤷ delegating: ${args.task.take(120)}"))

        val strategy = functionalStrategy<String, String>("koda-subagent") { input ->
            var response = requestLLM(input)
            var iterations = 0
            var calls = getToolCalls(response)
            while (calls.isNotEmpty() && iterations++ < maxIterations) {
                val results = executeTools(calls)
                response = sendToolResults(results)
                calls = getToolCalls(response)
            }
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
        }

        val subagent = AIAgent(
            promptExecutor = executor,
            llmModel = model,
            strategy = strategy,
            toolRegistry = scopedRegistry,
            systemPrompt = SUBAGENT_PROMPT,
            maxIterations = maxIterations * 3 + 8,
        )
        val result = subagent.run(args.task)
        events.emit(Notice(sessionId, "⤶ subagent done"))
        return result
    }

    private companion object {
        const val SUBAGENT_PROMPT =
            "You are a focused sub-agent spawned to investigate one task and report back. " +
            "Use the available read-only tools to explore, then answer concisely with your " +
            "findings — include concrete file paths and references. You cannot make changes; " +
            "do not ask the user questions, just investigate and summarize."
    }
}
