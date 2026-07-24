package dev.koda.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ClarifyRequest
import dev.koda.protocol.ClarifyResponse
import dev.koda.protocol.CreatePr
import dev.koda.protocol.DirEntry
import dev.koda.protocol.DirListing
import dev.koda.protocol.FileContent
import dev.koda.protocol.GetFile
import dev.koda.protocol.GetGitDiff
import dev.koda.protocol.GetGitStatus
import dev.koda.protocol.GitCommit
import dev.koda.protocol.GitCommitResult
import dev.koda.protocol.GitDiff
import dev.koda.protocol.GitFileChange
import dev.koda.protocol.GitStatus
import dev.koda.protocol.CancelSchedule
import dev.koda.protocol.CreateSchedule
import dev.koda.protocol.ListDir
import dev.koda.protocol.ListModels
import dev.koda.protocol.ListSchedules
import dev.koda.protocol.ModelList
import dev.koda.protocol.PrResult
import dev.koda.protocol.ReviewRequest
import dev.koda.protocol.ScheduleInfo
import dev.koda.protocol.ScheduleList
import dev.koda.protocol.SetModel
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.CheckpointInfo
import dev.koda.protocol.CheckpointList
import dev.koda.protocol.CompactSession
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Interrupt
import dev.koda.protocol.ListCheckpoints
import dev.koda.protocol.ListSessions
import dev.koda.protocol.LoadHistory
import dev.koda.protocol.Notice
import dev.koda.protocol.PermissionModeSetting
import dev.koda.protocol.ReasoningDelta
import dev.koda.protocol.Rewind
import dev.koda.protocol.SessionCompacted
import dev.koda.protocol.SessionHistory
import dev.koda.protocol.SessionList
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.SessionSummary
import dev.koda.protocol.SetPermissionMode
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStarted
import dev.koda.protocol.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** One rendered item in the transcript. Tool steps carry live status. */
sealed interface Line {
    val key: Int
    data class User(override val key: Int, val text: String) : Line
    data class Assistant(override val key: Int, val text: String) : Line
    data class Tool(override val key: Int, val name: String, val detail: String, val status: ToolStatus, val diff: String? = null, val meta: String = "") : Line
    data class Note(override val key: Int, val text: String, val error: Boolean = false) : Line
}

enum class ToolStatus { Running, Ok, Error }

/**
 * Folds the daemon's Event stream into observable Compose state and exposes the
 * actions the UI triggers. The whole app reads this; nothing else touches the
 * client. Runs the event collector on the caller's scope, off the UI thread.
 */
class AppModel(private val client: DaemonClient, private val scope: CoroutineScope) {
    var status by mutableStateOf(ConnStatus.Disconnected); private set
    var everConnected by mutableStateOf(false); private set
    var daemonUrl by mutableStateOf(System.getenv("KODA_DAEMON")?.takeIf { it.isNotBlank() } ?: "ws://127.0.0.1:4477")

    var sessionId by mutableStateOf(UUID.randomUUID().toString().take(8)); private set
    var modelName by mutableStateOf(""); private set
    var providerName by mutableStateOf(""); private set
    var contextLength by mutableStateOf(0L); private set
    var usedTokens by mutableStateOf(0L); private set
    var mode by mutableStateOf(PermissionModeSetting.DEFAULT); private set

    var working by mutableStateOf(false); private set
    var streaming by mutableStateOf(""); private set
    var reasoning by mutableStateOf(""); private set
    /** Human label of what the agent is doing right now (Thinking…, Running…, Writing …). */
    var activity by mutableStateOf<String?>(null); private set
    // Live turn telemetry, shown on the activity line.
    var turnStartMs by mutableStateOf(0L); private set
    var thoughtMs by mutableStateOf(0L); private set
    var turnOutChars by mutableStateOf(0); private set
    /** Final elapsed of the last completed turn, so the activity pill can read
     *  "Done · 8.4s" after streaming stops. */
    var lastTurnMs by mutableStateOf(0L); private set
    private var reasoningStartMs = 0L
    private var firstTextSeen = false

    val lines = mutableStateListOf<Line>()
    val sessions = mutableStateListOf<SessionSummary>()
    val checkpoints = mutableStateListOf<CheckpointInfo>()
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null); private set
    var pendingClarify by mutableStateOf<ClarifyRequest?>(null); private set

    // File explorer state
    val dirCache = mutableStateMapOf<String, List<DirEntry>>()
    val expandedDirs = mutableStateListOf<String>()
    var openFilePath by mutableStateOf<String?>(null); private set
    var openFileContent by mutableStateOf(""); private set
    var openFileError by mutableStateOf<String?>(null); private set

    // Git state
    var gitOk by mutableStateOf(false); private set
    var gitBranch by mutableStateOf(""); private set
    var gitAhead by mutableStateOf(0); private set
    var gitBehind by mutableStateOf(0); private set
    val gitFiles = mutableStateListOf<GitFileChange>()
    var gitDiff by mutableStateOf(""); private set
    var gitDiffPath by mutableStateOf<String?>(null); private set

    val schedules = mutableStateListOf<ScheduleInfo>()
    val availableModels = mutableStateListOf<String>()
    var activeModel by mutableStateOf(""); private set

    val contextPercent: Int?
        get() = if (contextLength > 0 && usedTokens > 0) ((usedTokens * 100) / contextLength).toInt().coerceAtMost(100) else null

    private var seq = 0
    private fun add(make: (Int) -> Line) { lines.add(make(seq++)) }
    private fun id() = UUID.randomUUID().toString()

    // Token-delta coalescing: append to a buffer and publish at most ~1/frame,
    // so a fast stream doesn't recompose the transcript on every character.
    private val deltaBuf = StringBuilder()
    private var flushScheduled = false
    private fun onDelta(text: String) {
        synchronized(deltaBuf) { deltaBuf.append(text) }
        if (!flushScheduled) {
            flushScheduled = true
            scope.launch {
                delay(40)
                flushScheduled = false
                streaming = synchronized(deltaBuf) { deltaBuf.toString() }
            }
        }
    }
    private val reasonBuf = StringBuilder()
    private var reasonFlushScheduled = false
    private fun onReason(text: String) {
        synchronized(reasonBuf) { reasonBuf.append(text) }
        if (!reasonFlushScheduled) {
            reasonFlushScheduled = true
            scope.launch {
                delay(40)
                reasonFlushScheduled = false
                reasoning = synchronized(reasonBuf) { reasonBuf.toString() }
            }
        }
    }
    private fun clearStream() {
        synchronized(deltaBuf) { deltaBuf.setLength(0) }
        synchronized(reasonBuf) { reasonBuf.setLength(0) }
        streaming = ""
        reasoning = ""
    }

    private fun activityFor(tool: String, argsJson: String): String {
        val file = Regex("\"file_path\"\\s*:\\s*\"([^\"]+)\"").find(argsJson)?.groupValues?.get(1)?.substringAfterLast('/')
        return when (tool) {
            "write", "edit" -> "Writing ${file ?: "file"}"
            "read" -> "Reading ${file ?: "file"}"
            "bash" -> "Running command"
            "grep", "glob" -> "Searching files"
            "web_search", "web_fetch" -> "Searching the web"
            "delegate" -> "Delegating to a subagent"
            else -> "Running $tool"
        }
    }

    /** The tool card's accent-soft target — the primary argument (path/pattern/command). */
    private fun toolTarget(tool: String, argsJson: String): String {
        fun arg(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { Regex("\"$it\"\\s*:\\s*\"([^\"]+)\"").find(argsJson)?.groupValues?.get(1) }
        return when (tool) {
            "read", "write", "edit" -> arg("file_path", "path") ?: ""
            "grep" -> arg("pattern", "query")?.let { "\"$it\"" }?.plus(arg("path")?.let { " $it" } ?: "") ?: ""
            "glob" -> arg("pattern") ?: ""
            "bash" -> arg("command")?.take(60) ?: ""
            "web_search" -> arg("query")?.let { "\"$it\"" } ?: ""
            "web_fetch" -> arg("url") ?: ""
            else -> arg("path", "file_path", "query", "pattern") ?: ""
        }
    }

    /** The tool card's faint right-aligned meta — a short result summary. */
    private fun toolMeta(tool: String, output: String, isError: Boolean): String {
        if (isError) return "error"
        val lines = output.count { it == '\n' } + if (output.isNotEmpty()) 1 else 0
        return when (tool) {
            "read" -> "$lines lines"
            "grep", "glob" -> "$lines matches"
            "bash" -> if (lines > 0) "$lines lines" else "done"
            "edit", "write" -> "done"
            else -> "done"
        }
    }

    init {
        client.onStatus = { s ->
            status = s
            if (s == ConnStatus.Connected && !everConnected) {
                everConnected = true
                refreshSessions(); refreshCheckpoints(); loadDir(""); refreshGit(); refreshSchedules()
                scope.launch { client.submit(ListModels(id(), sessionId)) }
            }
        }
        scope.launch {
            client.events.collect { ev ->
                when (ev) {
                    is SessionStarted -> if (ev.sessionId == sessionId) {
                        modelName = ev.model; providerName = ev.provider; contextLength = ev.contextLength
                    }
                    is TurnStarted -> if (ev.sessionId == sessionId) {
                        clearStream(); activity = "Thinking…"
                        turnStartMs = System.currentTimeMillis(); thoughtMs = 0; turnOutChars = 0
                        reasoningStartMs = 0; firstTextSeen = false
                    }
                    is ReasoningDelta -> if (ev.sessionId == sessionId) {
                        onReason(ev.text); activity = "Thinking…"
                        if (reasoningStartMs == 0L) reasoningStartMs = System.currentTimeMillis()
                    }
                    is TextDelta -> if (ev.sessionId == sessionId) {
                        onDelta(ev.text); activity = "Responding…"
                        if (!firstTextSeen) { firstTextSeen = true; if (reasoningStartMs > 0L) thoughtMs = System.currentTimeMillis() - reasoningStartMs }
                        turnOutChars += ev.text.length
                    }
                    is AssistantMessage -> {
                        if (ev.text.isNotBlank()) add { Line.Assistant(it, ev.text) }
                        clearStream(); activity = "Thinking…"
                    }
                    is ToolBegin -> {
                        clearStream()
                        activity = activityFor(ev.toolName, ev.argsJson)
                        add { Line.Tool(it, ev.toolName, toolTarget(ev.toolName, ev.argsJson), ToolStatus.Running, meta = "running…") }
                    }
                    is ToolEnd -> {
                        // mark the most recent running step of this tool done
                        val idx = lines.indexOfLast { it is Line.Tool && it.name == ev.toolName && it.status == ToolStatus.Running }
                        val st = if (ev.isError) ToolStatus.Error else ToolStatus.Ok
                        val meta = toolMeta(ev.toolName, ev.output, ev.isError)
                        if (idx >= 0) lines[idx] = (lines[idx] as Line.Tool).copy(status = st, diff = ev.diff, meta = meta)
                        else add { Line.Tool(it, ev.toolName, toolTarget(ev.toolName, ""), st, ev.diff, meta) }
                        activity = "Thinking…"
                    }
                    is ApprovalRequest -> if (ev.sessionId == sessionId) pendingApproval = ev
                    is ClarifyRequest -> if (ev.sessionId == sessionId) pendingClarify = ev
                    is TokenUsage -> if (ev.sessionId == sessionId) usedTokens = ev.inputTokens
                    is Notice -> if (ev.text.isNotBlank()) add { Line.Note(it, ev.text) }
                    is ErrorEvent -> add { Line.Note(it, "error: ${ev.message}", error = true) }
                    is SessionCompacted -> add { Line.Note(it, "compacted: ${ev.messagesBefore} → ${ev.messagesAfter} messages") }
                    is SessionList -> { sessions.clear(); sessions.addAll(ev.sessions) }
                    is DirListing -> dirCache[ev.path] = ev.entries
                    is FileContent -> { openFilePath = ev.path; openFileContent = ev.content; openFileError = ev.error }
                    is GitStatus -> {
                        gitOk = ev.ok; gitBranch = ev.branch; gitAhead = ev.ahead; gitBehind = ev.behind
                        gitFiles.clear(); gitFiles.addAll(ev.files)
                    }
                    is GitDiff -> { gitDiff = ev.unified; gitDiffPath = ev.path }
                    is ScheduleList -> { schedules.clear(); schedules.addAll(ev.schedules) }
                    is ModelList -> { availableModels.clear(); availableModels.addAll(ev.models); activeModel = ev.current }
                    is GitCommitResult -> { add { Line.Note(it, "git: ${ev.message}", error = !ev.ok) }; refreshGit() }
                    is PrResult -> add { Line.Note(it, "PR: ${ev.message}", error = !ev.ok) }
                    is CheckpointList -> if (ev.sessionId == sessionId) { checkpoints.clear(); checkpoints.addAll(ev.checkpoints) }
                    is dev.koda.protocol.RewindResult -> {
                        add { Line.Note(it, "⏪ ${ev.message}", error = !ev.ok) }
                        refreshCheckpoints()
                    }
                    is SessionHistory -> if (ev.sessionId == sessionId) {
                        lines.clear()
                        ev.messages.forEach { m -> if (m.role == "user") add { Line.User(it, m.text) } else add { Line.Assistant(it, m.text) } }
                        add { Line.Note(it, "— resumed $sessionId (${ev.messages.size} messages) —") }
                    }
                    is TurnCompleted -> if (ev.sessionId == sessionId) {
                        lastTurnMs = if (turnStartMs > 0) System.currentTimeMillis() - turnStartMs else 0
                        working = false; activity = null; clearStream(); refreshCheckpoints()
                    }
                    else -> {}
                }
            }
        }
    }

    // --- actions ---
    fun connect(url: String) { daemonUrl = url.trim(); client.connect(daemonUrl) }

    fun send(text: String, attachments: List<String> = emptyList()) {
        val t = text.trim(); if (t.isEmpty() && attachments.isEmpty()) return
        val shown = if (attachments.isEmpty()) t else "$t  📎${attachments.size}"
        add { Line.User(it, shown) }; working = true
        scope.launch { client.submit(UserTurn(id(), sessionId, t, attachments)) }
    }

    fun review(target: String) {
        working = true
        scope.launch { client.submit(ReviewRequest(id(), sessionId, target)) }
    }

    fun setModelId(modelId: String) {
        if (modelId.isNotBlank()) { activeModel = modelId; scope.launch { client.submit(SetModel(id(), sessionId, modelId)) } }
    }

    fun approve(decision: ApprovalDecision) {
        val p = pendingApproval ?: return
        pendingApproval = null
        scope.launch { client.submit(ApprovalResponse(id(), sessionId, p.approvalId, decision)) }
    }

    fun answerClarify(answer: String) {
        val c = pendingClarify ?: return
        pendingClarify = null
        scope.launch { client.submit(ClarifyResponse(id(), sessionId, c.clarifyId, answer)) }
    }

    fun rewind(steps: Int) { scope.launch { client.submit(Rewind(id(), sessionId, steps)) } }

    fun interrupt() { scope.launch { client.submit(Interrupt(id(), sessionId)) } }

    fun newSession() {
        sessionId = id().take(8); lines.clear(); checkpoints.clear(); usedTokens = 0
        add { Line.Note(it, "new session: $sessionId") }
    }

    fun resume(target: String) {
        sessionId = target; lines.clear(); checkpoints.clear(); usedTokens = 0
        scope.launch { client.submit(LoadHistory(id(), target)); client.submit(ListCheckpoints(id(), target)) }
    }

    fun compact() { scope.launch { client.submit(CompactSession(id(), sessionId)) } }

    fun changeMode(m: PermissionModeSetting) { mode = m; scope.launch { client.submit(SetPermissionMode(id(), sessionId, m)) } }

    fun refreshSessions() { scope.launch { client.submit(ListSessions(id(), sessionId)) } }
    fun refreshCheckpoints() { scope.launch { client.submit(ListCheckpoints(id(), sessionId)) } }

    fun loadDir(path: String) { scope.launch { client.submit(ListDir(id(), sessionId, path.ifEmpty { null })) } }
    fun toggleDir(path: String) {
        if (expandedDirs.contains(path)) expandedDirs.remove(path)
        else { expandedDirs.add(path); if (!dirCache.containsKey(path)) loadDir(path) }
    }
    fun openFile(path: String) { scope.launch { client.submit(GetFile(id(), sessionId, path)) } }
    fun closeFile() { openFilePath = null }

    fun refreshGit() { scope.launch { client.submit(GetGitStatus(id(), sessionId)) } }
    fun showGitDiff(path: String?) { scope.launch { client.submit(GetGitDiff(id(), sessionId, path, false)) } }
    fun gitCommit(message: String) { scope.launch { client.submit(GitCommit(id(), sessionId, message)) } }
    fun createPr(title: String) { scope.launch { client.submit(CreatePr(id(), sessionId, title, "")) } }

    fun refreshSchedules() { scope.launch { client.submit(ListSchedules(id(), sessionId)) } }
    fun createSchedule(prompt: String, everySeconds: Long) { scope.launch { client.submit(CreateSchedule(id(), sessionId, prompt, everySeconds)) } }
    fun cancelSchedule(scheduleId: String) { scope.launch { client.submit(CancelSchedule(id(), sessionId, scheduleId)) } }

    /**
     * Preview/render only — seed observable state with sample data so the UI can
     * be rendered offscreen (see :desktop:renderScreens) without a live daemon.
     * Never called in the running app.
     */
    internal fun previewSeed(startMs: Long) {
        status = ConnStatus.Connected; everConnected = true
        sessionId = "auth-fix"
        modelName = "claude-opus-4-8"; providerName = "Anthropic"; activeModel = "claude-opus-4-8"
        contextLength = 200_000; usedTokens = 124_000
        // Reply-complete ("Done") state: pill freezes at the final elapsed.
        working = false; activity = null; thoughtMs = 2_000; turnOutChars = 5_136; lastTurnMs = 8_400
        sessions.addAll(listOf(
            SessionSummary("ash-7f2", 0L, 14, "fix auth token compare"),
            SessionSummary("ash-3b1", 0L, 31, "migrate to pg pool"),
            SessionSummary("ash-9c4", 0L, 8, "refactor http client"),
            SessionSummary("ash-2e8", 0L, 22, "add rate limiting"),
            SessionSummary("ash-5a0", 0L, 5, "ci flakiness triage"),
        ))
        lines.add(Line.User(1, "The `auth` suite has a failing test — `verifyToken rejects valid sessions`. Find the bug and fix it."))
        val diff = """
            @@ -11,4 +11,6 @@ verifyToken
               if (!stored) return false
               const [ts, sig] = stored.split('.')
            -  return raw === sig
            +  const hashed = hmac(raw, SECRET)
            +  const expected = Buffer.from(sig, 'hex')
            +  return timingSafeEqual(hashed, expected)
             }
        """.trimIndent()
        // The edit_file card shows the fix hunk inline (expanded by default).
        val editDiff = """
            @@ -37,10 +37,12 @@ verifyToken
               if (!stored) return false
               const [ts, sig] = stored.split('.')
            -  return raw === sig
            -  // FIXME: rejects valid tokens
            +  const hashed = hmac(raw, SECRET)
            +  const expected = Buffer.from(sig, 'hex')
            +  return timingSafeEqual(hashed, expected)
             }
        """.trimIndent()
        lines.add(Line.Tool(2, "read_file", "tests/auth.test.ts", ToolStatus.Ok, meta = "42 lines"))
        lines.add(Line.Tool(3, "read_file", "src/auth.ts", ToolStatus.Ok, meta = "118 lines"))
        lines.add(Line.Tool(4, "edit_file", "src/auth.ts", ToolStatus.Ok, diff = editDiff))
        lines.add(Line.Tool(5, "run_shell", "npm test -- auth", ToolStatus.Ok, meta = "24 passed"))
        lines.add(Line.Assistant(6,
            "I traced the failure to verifyToken. It was comparing the raw incoming token directly against the stored hash, so every valid session was rejected. I hashed the token first and switched to a constant-time compare, then re-ran the auth suite — all 24 tests pass.\n\n" +
            "## What changed\n" +
            "- Hash the incoming token before comparison in `verifyToken`\n" +
            "- Swap `===` for `timingSafeEqual` to avoid timing leaks",
        ))
        checkpoints.addAll(listOf(
            CheckpointInfo(3, "Ran auth suite", 0),
            CheckpointInfo(2, "Edited src/auth.ts", 1),
            CheckpointInfo(1, "Read auth files", 3),
        ))
        gitOk = true; gitBranch = "fix/auth-token-compare"; gitAhead = 1
        gitFiles.add(GitFileChange("src/auth.ts", "modified", false))
        gitDiff = diff; gitDiffPath = "src/auth.ts"
        dirCache[""] = listOf(
            DirEntry("src", "src", true), DirEntry("tests", "tests", true),
            DirEntry("package.json", "package.json", false), DirEntry("README.md", "README.md", false),
            DirEntry(".env", ".env", false),
        )
        dirCache["src"] = listOf(
            DirEntry("auth.ts", "src/auth.ts", false), DirEntry("server.ts", "src/server.ts", false),
            DirEntry("lib", "src/lib", true),
        )
        dirCache["src/lib"] = listOf(DirEntry("crypto.ts", "src/lib/crypto.ts", false))
        dirCache["tests"] = listOf(DirEntry("auth.test.ts", "tests/auth.test.ts", false))
        expandedDirs.addAll(listOf("src", "src/lib", "tests"))
        openFilePath = "src/auth.ts"
        openFileContent = """
            import { hmac, timingSafeEqual } from './crypto'
            import { SECRET } from './config'

            export interface Session {
              user: string
              issued: number
            }

            // Verify a signed session token against the store
            export function verifyToken(raw: string, stored: string) {
              if (!stored) return false
              const [ts, sig] = stored.split('.')
              const hashed = hmac(raw, SECRET)
              const expected = Buffer.from(sig, 'hex')
              return timingSafeEqual(hashed, expected)
            }
        """.trimIndent()
        availableModels.addAll(listOf("claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5-20251001"))
    }
}
