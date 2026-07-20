package dev.koda.core

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.koda.core.adapter.ModePermissionPolicy
import dev.koda.core.adapter.PromptFileSessionRepository
import dev.koda.core.engine.KoogEngine
import dev.koda.core.engine.ToolGate
import dev.koda.core.engine.kodaToolRegistry
import dev.koda.core.port.PermissionPolicy
import dev.koda.core.port.SessionRepository
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.CompactSession
import dev.koda.protocol.Event
import dev.koda.protocol.Interrupt
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.Submission
import dev.koda.protocol.UserTurn
import dev.koda.tools.ToolContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * The Koda daemon core — a thin facade that routes protocol traffic.
 * [submit] takes [Submission]s, [events] emits [Event]s; sessions and the
 * Koog-backed [KoogEngine] do the actual work. All dependencies are
 * injected; use [KodaCore.create] as the default composition root.
 */
class KodaCore(
    private val config: KodaConfig,
    executor: PromptExecutor,
    model: LLModel,
    private val repository: SessionRepository,
    private val permissionPolicyFactory: () -> PermissionPolicy =
        { ModePermissionPolicy(config.permissionMode) },
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val submissions = Channel<Submission>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    private val sessions = ConcurrentHashMap<String, SessionRuntime>()

    private val engine = KoogEngine(
        executor = executor,
        model = model,
        events = { _events.emit(it) },
        maxIterationsPerTurn = config.maxIterationsPerTurn,
    )

    val events: SharedFlow<Event> get() = _events

    fun start(): Job = scope.launch {
        for (submission in submissions) {
            when (submission) {
                is UserTurn -> runtimeFor(submission.sessionId).enqueue(SessionWork.Turn(submission.text))
                is CompactSession -> runtimeFor(submission.sessionId).enqueue(SessionWork.Compact)
                is Interrupt -> sessions[submission.sessionId]?.interrupt()
                is ApprovalResponse ->
                    sessions[submission.sessionId]
                        ?.session?.approvals?.resolve(submission.approvalId, submission.decision)
            }
        }
    }

    suspend fun submit(submission: Submission) = submissions.send(submission)

    override fun close() {
        scope.cancel()
    }

    private suspend fun runtimeFor(sessionId: String): SessionRuntime {
        sessions[sessionId]?.let { return it }
        val session = AgentSession(
            id = sessionId,
            systemPrompt = SystemPrompt.build(config),
            toolContext = ToolContext(config.cwd),
            permissions = permissionPolicyFactory(),
            approvals = ApprovalBroker(),
            repository = repository,
        )
        val runtime = SessionRuntime(session)
        sessions[sessionId] = runtime
        _events.emit(SessionStarted(sessionId, config.model, config.provider.name, config.cwd.toString()))
        return runtime
    }

    private sealed interface SessionWork {
        data class Turn(val text: String) : SessionWork
        data object Compact : SessionWork
    }

    /** Orchestrates one session: serializes its work items, owns interrupt. */
    private inner class SessionRuntime(val session: AgentSession) {
        private val gate = ToolGate(session) { _events.emit(it) }
        private val registry = kodaToolRegistry(gate)
        private val workQueue = Channel<SessionWork>(Channel.UNLIMITED)
        @Volatile private var currentWork: Job? = null

        init {
            scope.launch {
                for (work in workQueue) {
                    val job = scope.launch {
                        when (work) {
                            is SessionWork.Turn -> engine.runTurn(session, registry, work.text)
                            is SessionWork.Compact -> engine.compact(session, registry)
                        }
                    }
                    currentWork = job
                    job.join()
                    currentWork = null
                }
            }
        }

        suspend fun enqueue(work: SessionWork) = workQueue.send(work)

        fun interrupt() {
            session.approvals.cancelAll()
            currentWork?.cancel()
        }
    }

    companion object {
        /** Default composition root: Koog executor per provider, file persistence, default tools. */
        fun create(config: KodaConfig): KodaCore = KodaCore(
            config = config,
            executor = buildExecutor(config.provider),
            model = buildModel(config),
            repository = PromptFileSessionRepository(config.sessionsDir),
        )

        private fun buildExecutor(provider: ProviderConfig): PromptExecutor {
            val httpFactory = KtorKoogHttpClient.Factory()
            return when (provider.apiShape) {
                ApiShape.ANTHROPIC_MESSAGES -> MultiLLMPromptExecutor(
                    AnthropicLLMClient(apiKey = provider.apiKey, httpClientFactory = httpFactory)
                )
                ApiShape.OPENAI_CHAT_COMPLETIONS -> MultiLLMPromptExecutor(
                    OpenAILLMClient(
                        apiKey = provider.apiKey,
                        settings = OpenAIClientSettings(baseUrl = provider.baseUrl),
                        httpClientFactory = httpFactory,
                    )
                )
            }
        }

        private fun buildModel(config: KodaConfig): LLModel = LLModel(
            provider = when (config.provider.apiShape) {
                ApiShape.ANTHROPIC_MESSAGES -> LLMProvider.Anthropic
                ApiShape.OPENAI_CHAT_COMPLETIONS -> LLMProvider.OpenAI
            },
            id = config.model,
            capabilities = buildList {
                add(LLMCapability.Completion)
                add(LLMCapability.Tools)
                add(LLMCapability.ToolChoice)
                add(LLMCapability.Temperature)
                if (config.provider.apiShape == ApiShape.OPENAI_CHAT_COMPLETIONS) {
                    add(LLMCapability.OpenAIEndpoint.Completions)
                }
            },
            contextLength = 200_000,
            maxOutputTokens = config.maxTokens.toLong(),
        )
    }
}
