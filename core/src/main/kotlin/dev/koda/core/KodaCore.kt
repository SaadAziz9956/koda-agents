package dev.koda.core

import dev.koda.core.adapter.JsonlSessionRepository
import dev.koda.core.adapter.ModePermissionPolicy
import dev.koda.core.port.PermissionPolicy
import dev.koda.core.port.SessionRepository
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.Event
import dev.koda.protocol.Interrupt
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.Submission
import dev.koda.protocol.UserTurn
import dev.koda.providers.ProviderTransport
import dev.koda.providers.createTransport
import dev.koda.tools.ToolContext
import dev.koda.tools.ToolRegistry
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
 * [AgentLoop] do the actual work. All dependencies are injected; use
 * [KodaCore.create] as the default composition root.
 */
class KodaCore(
    private val config: KodaConfig,
    private val transport: ProviderTransport,
    private val repository: SessionRepository,
    private val registry: ToolRegistry = ToolRegistry.default(),
    private val permissionPolicyFactory: () -> PermissionPolicy =
        { ModePermissionPolicy(config.permissionMode) },
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val submissions = Channel<Submission>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    private val sessions = ConcurrentHashMap<String, SessionRuntime>()

    private val loop = AgentLoop(
        transport = transport,
        registry = registry,
        events = { _events.emit(it) },
        model = config.model,
        maxTokens = config.maxTokens,
        maxIterationsPerTurn = config.maxIterationsPerTurn,
    )

    val events: SharedFlow<Event> get() = _events

    fun start(): Job = scope.launch {
        for (submission in submissions) {
            when (submission) {
                is UserTurn -> runtimeFor(submission.sessionId).enqueue(submission.text)
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
        transport.close()
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

    /** Orchestrates one session: serializes its turns, owns interrupt. */
    private inner class SessionRuntime(val session: AgentSession) {
        private val turnQueue = Channel<String>(Channel.UNLIMITED)
        @Volatile private var currentTurn: Job? = null

        init {
            scope.launch {
                for (text in turnQueue) {
                    val job = scope.launch { loop.runTurn(session, text) }
                    currentTurn = job
                    job.join()
                    currentTurn = null
                }
            }
        }

        suspend fun enqueue(text: String) = turnQueue.send(text)

        fun interrupt() {
            session.approvals.cancelAll()
            currentTurn?.cancel()
        }
    }

    companion object {
        /** Default composition root: real transport, JSONL persistence, default tools. */
        fun create(config: KodaConfig): KodaCore = KodaCore(
            config = config,
            transport = createTransport(config.provider),
            repository = JsonlSessionRepository(config.sessionsDir),
        )
    }
}
