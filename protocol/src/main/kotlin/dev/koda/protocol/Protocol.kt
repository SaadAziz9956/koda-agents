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

/** History compression finished (manual /compact or automatic). */
@Serializable
@SerialName("session_compacted")
data class SessionCompacted(
    override val sessionId: String,
    val tokensBefore: Long,
    val tokensAfter: Long,
) : Event

@Serializable
@SerialName("error")
data class ErrorEvent(
    override val sessionId: String,
    val message: String,
) : Event
