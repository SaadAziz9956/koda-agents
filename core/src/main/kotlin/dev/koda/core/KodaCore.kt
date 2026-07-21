package dev.koda.core

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import dev.koda.core.adapter.ModePermissionPolicy
import dev.koda.core.adapter.PromptFileSessionRepository
import ai.koog.agents.core.tools.ToolRegistry
import dev.koda.core.engine.DelegateTool
import dev.koda.core.engine.KoogEngine
import dev.koda.core.engine.McpConnection
import dev.koda.core.engine.McpConnector
import dev.koda.core.engine.ToolGate
import dev.koda.core.engine.gatedMcpRegistry
import dev.koda.core.engine.kodaExploreRegistry
import dev.koda.core.engine.kodaToolRegistry
import dev.koda.core.port.PermissionPolicy
import dev.koda.core.port.SessionRepository
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.CompactSession
import dev.koda.protocol.Event
import dev.koda.protocol.Interrupt
import dev.koda.protocol.ListMcpServers
import dev.koda.protocol.ListSessions
import dev.koda.protocol.ListSkills
import dev.koda.protocol.SkillList
import dev.koda.protocol.SkillSummary
import dev.koda.protocol.McpServerList
import dev.koda.protocol.McpServerSummary
import dev.koda.protocol.Notice
import dev.koda.protocol.PermissionModeSetting
import dev.koda.protocol.SessionList
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.SessionSummary
import dev.koda.protocol.SetPermissionMode
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
    private val executor: PromptExecutor,
    private val model: LLModel,
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

    private val mcpConnections = mutableListOf<McpConnection>()
    private val skills = SkillsLoader.load(config.kodaHome, config.cwd)

    val events: SharedFlow<Event> get() = _events

    fun start(): Job = scope.launch {
        connectMcpServers()
        for (submission in submissions) {
            when (submission) {
                is UserTurn -> runtimeFor(submission.sessionId).enqueue(SessionWork.Turn(submission.text))
                is CompactSession -> runtimeFor(submission.sessionId).enqueue(SessionWork.Compact)
                is Interrupt -> sessions[submission.sessionId]?.interrupt()
                is ApprovalResponse ->
                    sessions[submission.sessionId]
                        ?.session?.approvals?.resolve(submission.approvalId, submission.decision)
                is ListSessions -> _events.emit(
                    SessionList(
                        submission.sessionId,
                        repository.list().map { SessionSummary(it.id, it.updatedAtEpochMs, it.messageCount) },
                    )
                )
                is ListMcpServers -> _events.emit(
                    McpServerList(
                        submission.sessionId,
                        mcpConnections.map { McpServerSummary(it.name, it.toolNames) },
                    )
                )
                is ListSkills -> _events.emit(
                    SkillList(
                        submission.sessionId,
                        skills.values.sortedBy { it.name }.map { SkillSummary(it.name, it.description) },
                    )
                )
                is SetPermissionMode ->
                    runtimeFor(submission.sessionId).session.permissions.updateMode(
                        when (submission.mode) {
                            PermissionModeSetting.DEFAULT -> PermissionMode.DEFAULT
                            PermissionModeSetting.ACCEPT_EDITS -> PermissionMode.ACCEPT_EDITS
                            PermissionModeSetting.YOLO -> PermissionMode.YOLO
                        }
                    )
            }
        }
    }

    suspend fun submit(submission: Submission) = submissions.send(submission)

    /** Connect configured MCP servers before processing any submissions. */
    private suspend fun connectMcpServers() {
        McpConnector.loadSpecs(config.kodaHome, config.cwd).forEach { (name, spec) ->
            try {
                val connection = McpConnector.connect(name, spec)
                mcpConnections += connection
                _events.emit(
                    Notice("", "mcp: connected '$name' (${connection.toolNames.size} tools)")
                )
            } catch (e: Exception) {
                _events.emit(Notice("", "mcp: failed to connect '$name': ${e.message ?: e}"))
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun runtimeFor(sessionId: String): SessionRuntime {
        sessions[sessionId]?.let { return it }
        val session = AgentSession(
            id = sessionId,
            systemPrompt = SystemPrompt.build(config, skills),
            toolContext = ToolContext(config.cwd).apply { this.skills.putAll(this@KodaCore.skills) },
            permissions = permissionPolicyFactory(),
            approvals = ApprovalBroker(),
            repository = repository,
        )
        val runtime = SessionRuntime(session)
        sessions[sessionId] = runtime
        _events.emit(
            SessionStarted(
                sessionId,
                config.model,
                config.provider.name,
                config.cwd.toString(),
                contextLength = model.contextLength ?: 0,
            )
        )
        return runtime
    }

    private sealed interface SessionWork {
        data class Turn(val text: String) : SessionWork
        data object Compact : SessionWork
    }

    /** Orchestrates one session: serializes its work items, owns interrupt. */
    private inner class SessionRuntime(val session: AgentSession) {
        private val gate = ToolGate(session) { _events.emit(it) }
        private val delegate = ToolRegistry {
            tool(
                DelegateTool(
                    executor = executor,
                    model = model,
                    // Subagents explore with read-only tools, gated by this session.
                    scopedRegistry = kodaExploreRegistry(gate) + gatedMcpRegistry(mcpConnections, gate),
                    events = { _events.emit(it) },
                    sessionId = session.id,
                    maxIterations = config.maxIterationsPerTurn,
                )
            )
        }
        private val registry =
            kodaToolRegistry(gate) + delegate + gatedMcpRegistry(mcpConnections, gate)
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
        /**
         * Known Anthropic models: resolving from Koog's catalog inherits the
         * real context length and capabilities (prompt caching, vision, thinking).
         */
        private val ANTHROPIC_CATALOG: List<LLModel> = listOf(
            AnthropicModels.Fable_5,
            AnthropicModels.Opus_4_7,
            AnthropicModels.Opus_4_6,
            AnthropicModels.Opus_4_5,
            AnthropicModels.Opus_4_1,
            AnthropicModels.Opus_4,
            AnthropicModels.Sonnet_4_6,
            AnthropicModels.Sonnet_4_5,
            AnthropicModels.Sonnet_4,
            AnthropicModels.Haiku_4_5,
        )

        /** Default composition root: Koog executor per provider, file persistence, default tools. */
        fun create(config: KodaConfig): KodaCore {
            val model = buildModel(config)
            return KodaCore(
                config = config,
                executor = buildExecutor(config.provider, model),
                model = model,
                repository = PromptFileSessionRepository(config.sessionsDir),
            )
        }

        private fun buildExecutor(provider: ProviderConfig, model: LLModel): PromptExecutor {
            val httpFactory = KtorKoogHttpClient.Factory()
            return when (provider.apiShape) {
                ApiShape.ANTHROPIC_MESSAGES -> MultiLLMPromptExecutor(
                    AnthropicLLMClient(
                        apiKey = provider.apiKey,
                        // The client resolves the wire model id through this map;
                        // include the active model so custom ids work too.
                        settings = AnthropicClientSettings(
                            modelVersionsMap = ANTHROPIC_CATALOG.associateWith { it.id } +
                                (model to model.id),
                        ),
                        httpClientFactory = httpFactory,
                    )
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

        private fun buildModel(config: KodaConfig): LLModel = when (config.provider.apiShape) {
            ApiShape.ANTHROPIC_MESSAGES ->
                ANTHROPIC_CATALOG.firstOrNull { it.id == config.model }
                    ?: LLModel(
                        provider = LLMProvider.Anthropic,
                        id = config.model,
                        capabilities = listOf(
                            LLMCapability.Completion,
                            LLMCapability.Tools,
                            LLMCapability.ToolChoice,
                            LLMCapability.Temperature,
                            LLMCapability.PromptCaching,
                        ),
                        contextLength = 200_000,
                        maxOutputTokens = config.maxTokens.toLong(),
                    )

            ApiShape.OPENAI_CHAT_COMPLETIONS -> LLModel(
                provider = LLMProvider.OpenAI,
                id = config.model,
                capabilities = listOf(
                    LLMCapability.Completion,
                    LLMCapability.Tools,
                    LLMCapability.ToolChoice,
                    LLMCapability.Temperature,
                    LLMCapability.OpenAIEndpoint.Completions,
                ),
                contextLength = 200_000,
                maxOutputTokens = config.maxTokens.toLong(),
            )
        }
    }
}
