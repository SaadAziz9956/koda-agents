package dev.koda.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.koda.protocol.ApprovalDecision
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
    data class Tool(override val key: Int, val name: String, val detail: String, val status: ToolStatus, val diff: String? = null) : Line
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

    val lines = mutableStateListOf<Line>()
    val sessions = mutableStateListOf<SessionSummary>()
    val checkpoints = mutableStateListOf<CheckpointInfo>()
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null); private set

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

    init {
        client.onStatus = { s ->
            status = s
            if (s == ConnStatus.Connected && !everConnected) {
                everConnected = true
                refreshSessions(); refreshCheckpoints()
            }
        }
        scope.launch {
            client.events.collect { ev ->
                when (ev) {
                    is SessionStarted -> if (ev.sessionId == sessionId) {
                        modelName = ev.model; providerName = ev.provider; contextLength = ev.contextLength
                    }
                    is TurnStarted -> if (ev.sessionId == sessionId) clearStream()
                    is ReasoningDelta -> if (ev.sessionId == sessionId) onReason(ev.text)
                    is TextDelta -> if (ev.sessionId == sessionId) onDelta(ev.text)
                    is AssistantMessage -> {
                        if (ev.text.isNotBlank()) add { Line.Assistant(it, ev.text) }
                        clearStream()
                    }
                    is ToolBegin -> {
                        clearStream()
                        add { Line.Tool(it, ev.toolName, ev.argsJson.take(140), ToolStatus.Running) }
                    }
                    is ToolEnd -> {
                        // mark the most recent running step of this tool done
                        val idx = lines.indexOfLast { it is Line.Tool && it.name == ev.toolName && it.status == ToolStatus.Running }
                        val detail = ev.output.lineSequence().firstOrNull()?.take(120) ?: ""
                        val st = if (ev.isError) ToolStatus.Error else ToolStatus.Ok
                        if (idx >= 0) lines[idx] = (lines[idx] as Line.Tool).copy(detail = detail.ifBlank { "done" }, status = st, diff = ev.diff)
                        else add { Line.Tool(it, ev.toolName, detail, st, ev.diff) }
                    }
                    is ApprovalRequest -> if (ev.sessionId == sessionId) pendingApproval = ev
                    is TokenUsage -> if (ev.sessionId == sessionId) usedTokens = ev.inputTokens
                    is Notice -> if (ev.text.isNotBlank()) add { Line.Note(it, ev.text) }
                    is ErrorEvent -> add { Line.Note(it, "error: ${ev.message}", error = true) }
                    is SessionCompacted -> add { Line.Note(it, "compacted: ${ev.messagesBefore} → ${ev.messagesAfter} messages") }
                    is SessionList -> { sessions.clear(); sessions.addAll(ev.sessions) }
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
                        working = false; clearStream(); refreshCheckpoints()
                    }
                    else -> {}
                }
            }
        }
    }

    // --- actions ---
    fun connect(url: String) { daemonUrl = url.trim(); client.connect(daemonUrl) }

    fun send(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        add { Line.User(it, t) }; working = true
        scope.launch { client.submit(UserTurn(id(), sessionId, t)) }
    }

    fun approve(decision: ApprovalDecision) {
        val p = pendingApproval ?: return
        pendingApproval = null
        scope.launch { client.submit(ApprovalResponse(id(), sessionId, p.approvalId, decision)) }
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
}
