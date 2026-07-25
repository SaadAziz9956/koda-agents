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
import dev.koda.core.engine.HookLoader
import dev.koda.core.engine.HookRunner
import dev.koda.core.engine.KoogEngine
import dev.koda.core.engine.McpConnection
import dev.koda.core.engine.McpConnector
import dev.koda.core.engine.ToolGate
import dev.koda.core.engine.BwrapShellExecutor
import dev.koda.core.engine.EscalatedShellExecutor
import dev.koda.core.engine.EscalatingShellExecutor
import dev.koda.core.engine.Sandbox
import dev.koda.core.engine.SandboxPolicy
import dev.koda.core.engine.SeatbeltShellExecutor
import dev.koda.core.engine.gatedMcpRegistry
import dev.koda.core.engine.kodaScopedRegistry
import dev.koda.core.engine.kodaToolRegistry
import dev.koda.tools.DirectShellExecutor
import dev.koda.tools.ShellExecutor
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
    private val apiKeyHolder: dev.koda.core.engine.ApiKeyHolder = dev.koda.core.engine.ApiKeyHolder(),
    private val authStore: dev.koda.core.engine.AuthStore = dev.koda.core.engine.AuthStore(config.kodaHome),
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val submissions = Channel<Submission>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4096)
    private val sessions = ConcurrentHashMap<String, SessionRuntime>()
    private val checkpoints = ConcurrentHashMap<String, SessionCheckpoints>()
    private val scheduler = Scheduler(scope, config.kodaHome) { sid, prompt ->
        submit(UserTurn(java.util.UUID.randomUUID().toString(), sid, prompt))
    }

    private val hooks: HookRunner = HookLoader.load(config.kodaHome, config.cwd) { _events.emit(it) }

    private val sandboxActive: Boolean =
        config.sandbox != SandboxPolicy.DANGER_FULL_ACCESS &&
            (Sandbox.isMacSeatbeltAvailable() || Sandbox.isLinuxBwrapAvailable())
    private val sandboxNotice: String = when {
        config.sandbox == SandboxPolicy.DANGER_FULL_ACCESS -> "sandbox: off (danger-full-access)"
        Sandbox.isMacSeatbeltAvailable() -> "sandbox: seatbelt (${config.sandbox.name.lowercase()})"
        Sandbox.isLinuxBwrapAvailable() -> "sandbox: bubblewrap (${config.sandbox.name.lowercase()})"
        else -> "sandbox: unavailable (no seatbelt/bwrap) — shell runs unsandboxed"
    }

    private val mcpConnections = mutableListOf<McpConnection>()
    private val skills = SkillsLoader.load(config.kodaHome, config.cwd)
    private val memory = dev.koda.core.adapter.FileMemoryStore(config.kodaHome, config.cwd)
    private val skillWriter = dev.koda.core.adapter.FileSkillWriter(config.kodaHome)
    private val reviewer = if (config.autoMemory) dev.koda.core.engine.MemoryReviewer(executor, model) else null

    private val engine = KoogEngine(
        executor = executor,
        model = model,
        events = { _events.emit(it) },
        maxIterationsPerTurn = config.maxIterationsPerTurn,
        hooks = hooks,
        onTurnCompleted = { sessionId, userText, assistantText ->
            // Fire-and-forget background review; never blocks the turn.
            reviewer?.let { r ->
                scope.launch { r.reviewAndSave(sessionId, userText, assistantText, memory) { _events.emit(it) } }
            }
        },
    )

    val events: SharedFlow<Event> get() = _events

    fun start(): Job = scope.launch {
        _events.emit(Notice("", sandboxNotice))
        connectMcpServers()
        scheduler.loadAndStart()
        for (submission in submissions) {
            when (submission) {
                is UserTurn -> runtimeFor(submission.sessionId).enqueue(SessionWork.Turn(submission.text, submission.attachments))
                is CompactSession -> runtimeFor(submission.sessionId).enqueue(SessionWork.Compact)
                is Interrupt -> sessions[submission.sessionId]?.interrupt()
                is ApprovalResponse ->
                    sessions[submission.sessionId]
                        ?.session?.approvals?.resolve(submission.approvalId, submission.decision)
                is dev.koda.protocol.ClarifyResponse ->
                    sessions[submission.sessionId]
                        ?.session?.clarifications?.resolve(submission.clarifyId, submission.answer)
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
                is dev.koda.protocol.LoadHistory -> {
                    val history = repository.load(submission.sessionId)?.messages.orEmpty().mapNotNull { m ->
                        val role = when (m.role) {
                            ai.koog.prompt.message.Message.Role.User -> "user"
                            ai.koog.prompt.message.Message.Role.Assistant -> "assistant"
                            else -> return@mapNotNull null
                        }
                        m.textContent().takeIf { it.isNotBlank() }?.let { dev.koda.protocol.HistoryMessage(role, it) }
                    }
                    _events.emit(dev.koda.protocol.SessionHistory(submission.sessionId, history))
                }
                is dev.koda.protocol.ListCheckpoints -> {
                    val rows = checkpoints[submission.sessionId]?.list().orEmpty()
                    _events.emit(
                        dev.koda.protocol.CheckpointList(
                            submission.sessionId,
                            rows.mapIndexed { i, (label, files) ->
                                dev.koda.protocol.CheckpointInfo(i + 1, label, files)
                            },
                        )
                    )
                }
                is dev.koda.protocol.CreatePr -> {
                    val (ok, msg) = GitService.createPr(config.cwd, submission.title, submission.body)
                    _events.emit(dev.koda.protocol.PrResult(submission.sessionId, ok, msg))
                }
                is dev.koda.protocol.CreateSchedule -> {
                    val list = scheduler.create(submission.sessionId, submission.prompt, submission.everySeconds)
                    _events.emit(dev.koda.protocol.ScheduleList(submission.sessionId, list.map { dev.koda.protocol.ScheduleInfo(it.id, it.prompt, it.everySeconds) }))
                }
                is dev.koda.protocol.CancelSchedule -> {
                    val list = scheduler.cancel(submission.scheduleId)
                    _events.emit(dev.koda.protocol.ScheduleList(submission.sessionId, list.map { dev.koda.protocol.ScheduleInfo(it.id, it.prompt, it.everySeconds) }))
                }
                is dev.koda.protocol.ListSchedules -> _events.emit(
                    dev.koda.protocol.ScheduleList(submission.sessionId, scheduler.list().map { dev.koda.protocol.ScheduleInfo(it.id, it.prompt, it.everySeconds) })
                )
                is dev.koda.protocol.ReviewRequest -> {
                    val diff = GitService.reviewDiff(config.cwd, submission.target)
                    if (diff.isBlank()) {
                        _events.emit(Notice(submission.sessionId, "review: no changes found for '${submission.target}'"))
                    } else {
                        val capped = if (diff.length > 60_000) diff.take(60_000) + "\n… (diff truncated)" else diff
                        val prompt = """
                            Review the following code changes (target: ${submission.target}). Act as a
                            careful senior reviewer. Do NOT modify any files — this is review only.
                            Report prioritized findings (most severe first): correctness/bugs, security,
                            performance, and clarity. For each: the file, what's wrong, and the fix.
                            If it looks good, say so briefly.

                            ```diff
                            $capped
                            ```
                        """.trimIndent()
                        runtimeFor(submission.sessionId).enqueue(SessionWork.Turn(prompt))
                    }
                }
                is dev.koda.protocol.ListDir -> {
                    val listed = WorkspaceService.list(config.cwd, submission.path)
                    if (listed == null) {
                        _events.emit(dev.koda.protocol.DirListing(submission.sessionId, submission.path.orEmpty(), emptyList()))
                    } else {
                        _events.emit(
                            dev.koda.protocol.DirListing(
                                submission.sessionId, listed.first,
                                listed.second.map { dev.koda.protocol.DirEntry(it.name, it.relPath, it.isDir) },
                            )
                        )
                    }
                }
                is dev.koda.protocol.GetFile -> {
                    val r = WorkspaceService.read(config.cwd, submission.path)
                    _events.emit(
                        dev.koda.protocol.FileContent(submission.sessionId, submission.path, r.content, r.truncated, r.error)
                    )
                }
                is dev.koda.protocol.GetGitStatus -> {
                    val s = GitService.status(config.cwd)
                    _events.emit(
                        dev.koda.protocol.GitStatus(
                            submission.sessionId, s.ok, s.branch, s.ahead, s.behind,
                            s.files.map { dev.koda.protocol.GitFileChange(it.path, it.status, it.staged) },
                        )
                    )
                }
                is dev.koda.protocol.GetGitDiff -> _events.emit(
                    dev.koda.protocol.GitDiff(
                        submission.sessionId, submission.path,
                        GitService.diff(config.cwd, submission.path, submission.staged),
                    )
                )
                is dev.koda.protocol.GitCommit -> {
                    val (ok, msg) = GitService.commit(config.cwd, submission.message)
                    _events.emit(dev.koda.protocol.GitCommitResult(submission.sessionId, ok, msg))
                }
                is dev.koda.protocol.Rewind -> {
                    val outcome = checkpoints[submission.sessionId]?.rewind(submission.steps)
                    if (outcome == null) {
                        _events.emit(
                            dev.koda.protocol.RewindResult(
                                submission.sessionId, ok = false, steps = 0, messagesAfter = 0,
                                filesRestored = emptyList(), message = "nothing to rewind",
                            )
                        )
                    } else {
                        val session = sessions[submission.sessionId]?.session
                        session?.let { it.prompt = outcome.promptBefore; it.persist() }
                        val messages = outcome.promptBefore?.messages?.size ?: 0
                        val files = outcome.filesRestored
                        val fileNote = if (files.isEmpty()) "no files changed" else "${files.size} file(s) reverted"
                        _events.emit(
                            dev.koda.protocol.RewindResult(
                                submission.sessionId, ok = true, steps = outcome.steps,
                                messagesAfter = messages, filesRestored = files,
                                message = "rewound ${outcome.steps} turn(s); $fileNote; conversation now $messages messages",
                            )
                        )
                    }
                }
                is dev.koda.protocol.ListModels -> {
                    val models = when (config.provider.apiShape) {
                        ApiShape.ANTHROPIC_MESSAGES -> ANTHROPIC_CATALOG.map { it.id }
                        ApiShape.OPENAI_CHAT_COMPLETIONS -> listOf(config.model)
                    }
                    val current = sessions[submission.sessionId]?.session?.modelOverride?.id ?: config.model
                    _events.emit(dev.koda.protocol.ModelList(submission.sessionId, models, current))
                }
                is dev.koda.protocol.SetModel -> {
                    val resolved = resolveModel(submission.modelId)
                    if (resolved == null) {
                        _events.emit(Notice(submission.sessionId, "unknown model '${submission.modelId}' for this provider"))
                    } else {
                        runtimeFor(submission.sessionId).session.modelOverride = resolved
                        _events.emit(Notice(submission.sessionId, "model → ${submission.modelId}"))
                    }
                }
                is SetPermissionMode ->
                    runtimeFor(submission.sessionId).session.permissions.updateMode(
                        when (submission.mode) {
                            PermissionModeSetting.DEFAULT -> PermissionMode.DEFAULT
                            PermissionModeSetting.ACCEPT_EDITS -> PermissionMode.ACCEPT_EDITS
                            PermissionModeSetting.YOLO -> PermissionMode.YOLO
                            PermissionModeSetting.PLAN -> PermissionMode.PLAN
                        }
                    )
                is dev.koda.protocol.SetApiKey -> handleSetApiKey(submission)
                is dev.koda.protocol.GetAuthStatus -> _events.emit(authStatusEvent(submission.sessionId))
                is dev.koda.protocol.SignOut -> {
                    authStore.clear(); apiKeyHolder.key = null; authLabel = ""
                    _events.emit(authStatusEvent(submission.sessionId))
                }
            }
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────
    @Volatile private var authLabel: String = ""

    /** Validate a submitted key; on success stamp the live holder + persist it. */
    private suspend fun handleSetApiKey(sub: dev.koda.protocol.SetApiKey) {
        val check = dev.koda.core.engine.validateAnthropicKey(sub.key)
        if (check.ok) {
            apiKeyHolder.key = sub.key
            authStore.saveAnthropicKey(sub.key)
            authLabel = check.label
            _events.emit(dev.koda.protocol.AuthStatus(sub.sessionId, configured = true, label = check.label))
        } else {
            _events.emit(dev.koda.protocol.AuthStatus(sub.sessionId, configured = false, label = authLabel, error = check.error))
        }
    }

    private fun authStatusEvent(sessionId: String) = dev.koda.protocol.AuthStatus(
        sessionId, configured = !apiKeyHolder.key.isNullOrBlank(), label = authLabel,
    )

    suspend fun submit(submission: Submission) = submissions.send(submission)

    /** Resolve a model id for the active provider; null if unknown (Anthropic is catalog-bound). */
    private fun resolveModel(id: String): LLModel? = when (config.provider.apiShape) {
        ApiShape.ANTHROPIC_MESSAGES -> ANTHROPIC_CATALOG.firstOrNull { it.id == id }
        ApiShape.OPENAI_CHAT_COMPLETIONS -> LLModel(
            provider = LLMProvider.OpenAI,
            id = id,
            capabilities = listOf(
                LLMCapability.Completion, LLMCapability.Tools, LLMCapability.ToolChoice,
                LLMCapability.Temperature, LLMCapability.OpenAIEndpoint.Completions,
            ),
            contextLength = 200_000,
            maxOutputTokens = config.maxTokens.toLong(),
        )
    }

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
        val sessionCheckpoints = SessionCheckpoints()
        checkpoints[sessionId] = sessionCheckpoints
        val session = AgentSession(
            id = sessionId,
            systemPrompt = SystemPrompt.build(config, skills, memory),
            toolContext = ToolContext(config.cwd).apply {
                this.skills.putAll(this@KodaCore.skills)
                snapshotSink = dev.koda.tools.FileSnapshotSink { sessionCheckpoints.capture(it) }
            },
            permissions = permissionPolicyFactory(),
            approvals = ApprovalBroker(),
            repository = repository,
        )
        val runtime = SessionRuntime(session)
        sessions[sessionId] = runtime
        hooks.fireSessionStart(sessionId, config.cwd)
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
        data class Turn(val text: String, val attachments: List<String> = emptyList()) : SessionWork
        data object Compact : SessionWork
    }

    /** Escalation approval: emit a distinct request and await the user's answer. */
    private suspend fun approveEscalation(session: AgentSession, command: String): Boolean {
        // If bash wouldn't prompt at all (yolo / always-allowed), don't prompt to escalate either.
        if (!session.permissions.needsApproval("bash", mutating = true)) return true
        val approvalId = java.util.UUID.randomUUID().toString()
        _events.emit(
            dev.koda.protocol.ApprovalRequest(
                session.id, approvalId, "bash",
                "⚠ run WITHOUT sandbox (it was blocked): ${command.take(100)}", command,
            )
        )
        return when (session.approvals.await(approvalId)) {
            dev.koda.protocol.ApprovalDecision.DENY -> false
            else -> true
        }
    }

    private fun shellFor(session: AgentSession): ShellExecutor {
        if (!sandboxActive) return DirectShellExecutor()
        val sandboxed = if (Sandbox.isMacSeatbeltAvailable()) SeatbeltShellExecutor(config.sandbox)
        else BwrapShellExecutor(config.sandbox)
        return EscalatingShellExecutor(
            sandboxed = sandboxed,
            // Escalation widens writes + network but NEVER exposes secrets.
            direct = EscalatedShellExecutor(),
            approveEscalation = { cmd -> approveEscalation(session, cmd) },
        )
    }

    /** Orchestrates one session: serializes its work items, owns interrupt. */
    private inner class SessionRuntime(val session: AgentSession) {
        private val gate = ToolGate(session, { _events.emit(it) }, hooks)
        private val shell = shellFor(session)
        private val delegate = ToolRegistry {
            tool(
                DelegateTool(
                    executor = executor,
                    model = model,
                    // Subagent tools are chosen per call (default read-only), gated by this session.
                    registryFor = { names ->
                        kodaScopedRegistry(gate, shell, names) + gatedMcpRegistry(mcpConnections, gate)
                    },
                    events = { _events.emit(it) },
                    sessionId = session.id,
                    maxIterations = config.maxIterationsPerTurn,
                )
            )
        }
        private val registry =
            kodaToolRegistry(gate, shell, memory, skillWriter) + delegate + gatedMcpRegistry(mcpConnections, gate)
        private val workQueue = Channel<SessionWork>(Channel.UNLIMITED)
        @Volatile private var currentWork: Job? = null

        init {
            scope.launch {
                for (work in workQueue) {
                    val job = scope.launch {
                        when (work) {
                            is SessionWork.Turn -> {
                                // Open an undo checkpoint over the committed pre-turn state.
                                checkpoints[session.id]?.begin(session.prompt, work.text)
                                engine.runTurn(session, registry, work.text, work.attachments)
                            }
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
            session.clarifications.cancelAll()
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
            // The live key comes from the store, then env/config, then unset —
            // it's injected per-request, so it can be set from the app later.
            val authStore = dev.koda.core.engine.AuthStore(config.kodaHome)
            val holder = dev.koda.core.engine.ApiKeyHolder(
                authStore.loadAnthropicKey() ?: config.provider.apiKey.ifBlank { null },
            )
            return KodaCore(
                config = config,
                executor = buildExecutor(config.provider, model, holder),
                model = model,
                repository = PromptFileSessionRepository(config.sessionsDir),
                apiKeyHolder = holder,
                authStore = authStore,
            )
        }

        private fun buildExecutor(
            provider: ProviderConfig,
            model: LLModel,
            holder: dev.koda.core.engine.ApiKeyHolder,
        ): PromptExecutor {
            return when (provider.apiShape) {
                ApiShape.ANTHROPIC_MESSAGES -> {
                    // Base Ktor client stamps the holder's current key onto every
                    // request; the client's own apiKey is a placeholder that the
                    // plugin overwrites. Setting a key at runtime takes effect at once.
                    val base = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO) {
                        install(dev.koda.core.engine.apiKeyPlugin(holder))
                    }
                    MultiLLMPromptExecutor(
                        AnthropicLLMClient(
                            apiKey = provider.apiKey.ifBlank { "koda-unset" },
                            settings = AnthropicClientSettings(
                                modelVersionsMap = ANTHROPIC_CATALOG.associateWith { it.id } +
                                    (model to model.id),
                            ),
                            httpClientFactory = KtorKoogHttpClient.Factory(base, true),
                        )
                    )
                }
                ApiShape.OPENAI_CHAT_COMPLETIONS -> MultiLLMPromptExecutor(
                    OpenAILLMClient(
                        apiKey = provider.apiKey,
                        settings = OpenAIClientSettings(baseUrl = provider.baseUrl),
                        httpClientFactory = KtorKoogHttpClient.Factory(),
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
