package dev.koda.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Koda wire protocol: the single boundary between the core (daemon) and
 * every surface (CLI, TUI, gateway, desktop, SDK). Clients send [Submission]s,
 * the core emits [Event]s. Carried in-process over channels, or serialized as
 * JSON lines over stdio / WebSocket. Nothing on either side of this boundary
 * may depend on anything but these types.
 */

val ProtocolJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Submissions: client -> core
// ---------------------------------------------------------------------------

@Serializable
sealed interface Submission {
    val id: String
    val sessionId: String
}

/** Start (or queue) a user turn in a session. */
@Serializable
@SerialName("user_turn")
data class UserTurn(
    override val id: String,
    override val sessionId: String,
    val text: String,
) : Submission

/** Cancel the currently running turn, keeping the session alive. */
@Serializable
@SerialName("interrupt")
data class Interrupt(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Answer to a pending [ApprovalRequest]. */
@Serializable
@SerialName("approval_response")
data class ApprovalResponse(
    override val id: String,
    override val sessionId: String,
    val approvalId: String,
    val decision: ApprovalDecision,
) : Submission

/** Compress the session's conversation history into a summary. */
@Serializable
@SerialName("compact_session")
data class CompactSession(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Ask the core for the list of persisted sessions. Answered with [SessionList]. */
@Serializable
@SerialName("list_sessions")
data class ListSessions(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Ask the core for connected MCP servers. Answered with [McpServerList]. */
@Serializable
@SerialName("list_mcp_servers")
data class ListMcpServers(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Ask the core for available skills. Answered with [SkillList]. */
@Serializable
@SerialName("list_skills")
data class ListSkills(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Ask the core to replay a session's prior messages. Answered with [SessionHistory]. */
@Serializable
@SerialName("load_history")
data class LoadHistory(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Ask the core for the session's undo checkpoints. Answered with [CheckpointList]. */
@Serializable
@SerialName("list_checkpoints")
data class ListCheckpoints(
    override val id: String,
    override val sessionId: String,
) : Submission

/**
 * Undo the last [steps] turns: restore the conversation to before them and
 * revert files those turns changed via the write/edit tools. Answered with
 * [RewindResult].
 */
@Serializable
@SerialName("rewind")
data class Rewind(
    override val id: String,
    override val sessionId: String,
    val steps: Int = 1,
) : Submission

/** Change the permission mode of a running session. */
@Serializable
@SerialName("set_permission_mode")
data class SetPermissionMode(
    override val id: String,
    override val sessionId: String,
    val mode: PermissionModeSetting,
) : Submission

@Serializable
enum class PermissionModeSetting {
    @SerialName("default") DEFAULT,
    @SerialName("accept_edits") ACCEPT_EDITS,
    @SerialName("yolo") YOLO,
}

@Serializable
enum class ApprovalDecision {
    @SerialName("approve") APPROVE,
    @SerialName("approve_always") APPROVE_ALWAYS,
    @SerialName("deny") DENY,
}

// ---------------------------------------------------------------------------
// Events: core -> client
// ---------------------------------------------------------------------------

@Serializable
sealed interface Event {
    val sessionId: String
}

@Serializable
@SerialName("session_started")
data class SessionStarted(
    override val sessionId: String,
    val model: String,
    val provider: String,
    val cwd: String,
    /** Model context window in tokens; 0 when unknown. */
    val contextLength: Long = 0,
) : Event

/** Answer to [ListSessions]. */
@Serializable
@SerialName("session_list")
data class SessionList(
    override val sessionId: String,
    val sessions: List<SessionSummary>,
) : Event

@Serializable
data class SessionSummary(
    val id: String,
    val updatedAtEpochMs: Long,
    val messageCount: Int,
)

/** Answer to [ListMcpServers]. */
@Serializable
@SerialName("mcp_server_list")
data class McpServerList(
    override val sessionId: String,
    val servers: List<McpServerSummary>,
) : Event

@Serializable
data class McpServerSummary(
    val name: String,
    val toolNames: List<String>,
)

/** Answer to [ListSkills]. */
@Serializable
@SerialName("skill_list")
data class SkillList(
    override val sessionId: String,
    val skills: List<SkillSummary>,
) : Event

@Serializable
data class SkillSummary(
    val name: String,
    val description: String,
)

/** Answer to [LoadHistory]: a session's prior user/assistant messages, in order. */
@Serializable
@SerialName("session_history")
data class SessionHistory(
    override val sessionId: String,
    val messages: List<HistoryMessage>,
) : Event

@Serializable
data class HistoryMessage(val role: String, val text: String)

/** Answer to [ListCheckpoints]: undo points, newest first (index 1 = most recent turn). */
@Serializable
@SerialName("checkpoint_list")
data class CheckpointList(
    override val sessionId: String,
    val checkpoints: List<CheckpointInfo>,
) : Event

@Serializable
data class CheckpointInfo(val index: Int, val label: String, val fileCount: Int)

/** Answer to [Rewind]. */
@Serializable
@SerialName("rewind_result")
data class RewindResult(
    override val sessionId: String,
    val ok: Boolean,
    /** Turns actually undone (may be fewer than requested). */
    val steps: Int,
    /** Conversation message count after the rewind. */
    val messagesAfter: Int,
    /** Paths reverted (restored or deleted) by the rewind. */
    val filesRestored: List<String>,
    val message: String,
) : Event

/** Informational message from the core (startup notices, MCP connect results). */
@Serializable
@SerialName("notice")
data class Notice(
    override val sessionId: String,
    val text: String,
) : Event

@Serializable
@SerialName("turn_started")
data class TurnStarted(
    override val sessionId: String,
    val turnId: String,
) : Event

/** Streamed chunk of assistant text. */
@Serializable
@SerialName("text_delta")
data class TextDelta(
    override val sessionId: String,
    val turnId: String,
    val text: String,
) : Event

/** Streamed chunk of model reasoning/thinking, when the provider surfaces it. */
@Serializable
@SerialName("reasoning_delta")
data class ReasoningDelta(
    override val sessionId: String,
    val turnId: String,
    val text: String,
) : Event

/** Finalized assistant message for the iteration (post-stream). */
@Serializable
@SerialName("assistant_message")
data class AssistantMessage(
    override val sessionId: String,
    val turnId: String,
    val text: String,
) : Event

@Serializable
@SerialName("tool_begin")
data class ToolBegin(
    override val sessionId: String,
    val turnId: String,
    val callId: String,
    val toolName: String,
    val argsJson: String,
) : Event

@Serializable
@SerialName("tool_end")
data class ToolEnd(
    override val sessionId: String,
    val turnId: String,
    val callId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean = false,
) : Event

/**
 * The core is blocked waiting for the user to approve a tool call.
 * Answered with [ApprovalResponse].
 */
@Serializable
@SerialName("approval_request")
data class ApprovalRequest(
    override val sessionId: String,
    val approvalId: String,
    val toolName: String,
    val summary: String,
    val argsJson: String,
) : Event

@Serializable
@SerialName("token_usage")
data class TokenUsage(
    override val sessionId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
) : Event

@Serializable
@SerialName("turn_completed")
data class TurnCompleted(
    override val sessionId: String,
    val turnId: String,
    val stopReason: TurnStopReason,
) : Event

@Serializable
enum class TurnStopReason {
    @SerialName("completed") COMPLETED,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("max_iterations") MAX_ITERATIONS,
    @SerialName("error") ERROR,
}

/**
 * History compression finished (manual /compact or automatic). Reported as
 * message counts — a truthful before/after, unlike last-request token usage
 * which reflects the summarization call itself.
 */
@Serializable
@SerialName("session_compacted")
data class SessionCompacted(
    override val sessionId: String,
    val messagesBefore: Int,
    val messagesAfter: Int,
) : Event

@Serializable
@SerialName("error")
data class ErrorEvent(
    override val sessionId: String,
    val message: String,
) : Event
