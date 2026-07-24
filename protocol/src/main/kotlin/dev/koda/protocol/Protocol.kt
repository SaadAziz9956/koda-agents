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
    /** Image attachments: local file paths, http(s) URLs, or data: URIs. */
    val attachments: List<String> = emptyList(),
) : Submission

/** Cancel the currently running turn, keeping the session alive. */
@Serializable
@SerialName("interrupt")
data class Interrupt(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Answer to a pending [ClarifyRequest]. `answer` is an option label, free text, or the chat sentinel. */
@Serializable
@SerialName("clarify_response")
data class ClarifyResponse(
    override val id: String,
    override val sessionId: String,
    val clarifyId: String,
    val answer: String,
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

/** Ask the core for the working-tree git status. Answered with [GitStatus]. */
@Serializable
@SerialName("get_git_status")
data class GetGitStatus(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Ask the core for a unified diff (whole tree, or one [path]). Answered with [GitDiff]. */
@Serializable
@SerialName("get_git_diff")
data class GetGitDiff(
    override val id: String,
    override val sessionId: String,
    val path: String? = null,
    /** Diff staged changes against HEAD instead of the working tree. */
    val staged: Boolean = false,
) : Submission

/** Stage all changes and commit. Answered with [GitCommitResult]. */
@Serializable
@SerialName("git_commit")
data class GitCommit(
    override val id: String,
    override val sessionId: String,
    val message: String,
) : Submission

/** Push the current branch and open a pull request (via `gh`). Answered with [PrResult]. */
@Serializable
@SerialName("create_pr")
data class CreatePr(
    override val id: String,
    override val sessionId: String,
    val title: String,
    val body: String = "",
) : Submission

/** Create a recurring scheduled task (fires [prompt] every [everySeconds]). Answered with [ScheduleList]. */
@Serializable
@SerialName("create_schedule")
data class CreateSchedule(
    override val id: String,
    override val sessionId: String,
    val prompt: String,
    val everySeconds: Long,
) : Submission

/** Cancel a scheduled task. Answered with [ScheduleList]. */
@Serializable
@SerialName("cancel_schedule")
data class CancelSchedule(
    override val id: String,
    override val sessionId: String,
    val scheduleId: String,
) : Submission

/** List scheduled tasks. Answered with [ScheduleList]. */
@Serializable
@SerialName("list_schedules")
data class ListSchedules(
    override val id: String,
    override val sessionId: String,
) : Submission

/** List a workspace directory (relative to the workspace root). Answered with [DirListing]. */
@Serializable
@SerialName("list_dir")
data class ListDir(
    override val id: String,
    override val sessionId: String,
    /** Path relative to the workspace root; null/empty = root. */
    val path: String? = null,
) : Submission

/** Read a workspace file's contents. Answered with [FileContent]. */
@Serializable
@SerialName("get_file")
data class GetFile(
    override val id: String,
    override val sessionId: String,
    val path: String,
) : Submission

/**
 * Run a code review over a git target and stream findings as a normal turn.
 * [target]: "uncommitted" (default), "staged", a commit ref, or a "base..head"
 * / branch range.
 */
@Serializable
@SerialName("review_request")
data class ReviewRequest(
    override val id: String,
    override val sessionId: String,
    val target: String = "uncommitted",
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

/** Ask the core which models the active provider offers. Answered with [ModelList]. */
@Serializable
@SerialName("list_models")
data class ListModels(
    override val id: String,
    override val sessionId: String,
) : Submission

/** Switch the model for a session at runtime. Acked with a [Notice]. */
@Serializable
@SerialName("set_model")
data class SetModel(
    override val id: String,
    override val sessionId: String,
    val modelId: String,
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
    @SerialName("plan") PLAN,
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
    /** Optional short human title/summary of the session, if the daemon has one. */
    val title: String? = null,
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

/** Answer to [ListModels]: model ids the active provider offers, and the current one. */
@Serializable
@SerialName("model_list")
data class ModelList(
    override val sessionId: String,
    val models: List<String>,
    val current: String,
) : Event

/** Answer to [ListCheckpoints]: undo points, newest first (index 1 = most recent turn). */
@Serializable
@SerialName("checkpoint_list")
data class CheckpointList(
    override val sessionId: String,
    val checkpoints: List<CheckpointInfo>,
) : Event

@Serializable
data class CheckpointInfo(val index: Int, val label: String, val fileCount: Int)

/** Answer to [GetGitStatus]. `ok=false` when the cwd isn't a git repo. */
@Serializable
@SerialName("git_status")
data class GitStatus(
    override val sessionId: String,
    val ok: Boolean,
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val files: List<GitFileChange>,
) : Event

@Serializable
data class GitFileChange(val path: String, val status: String, val staged: Boolean)

/** Answer to [GetGitDiff]: a unified diff (may be empty). */
@Serializable
@SerialName("git_diff")
data class GitDiff(
    override val sessionId: String,
    val path: String?,
    val unified: String,
) : Event

/** Answer to [GitCommit]. */
@Serializable
@SerialName("git_commit_result")
data class GitCommitResult(
    override val sessionId: String,
    val ok: Boolean,
    val message: String,
) : Event

/** Answer to [CreatePr]. `message` is the PR URL on success. */
@Serializable
@SerialName("pr_result")
data class PrResult(
    override val sessionId: String,
    val ok: Boolean,
    val message: String,
) : Event

/** Answer to schedule submissions: the current set of scheduled tasks. */
@Serializable
@SerialName("schedule_list")
data class ScheduleList(
    override val sessionId: String,
    val schedules: List<ScheduleInfo>,
) : Event

@Serializable
data class ScheduleInfo(val id: String, val prompt: String, val everySeconds: Long)

/** Answer to [ListDir]: directory entries, directories first. */
@Serializable
@SerialName("dir_listing")
data class DirListing(
    override val sessionId: String,
    val path: String,
    val entries: List<DirEntry>,
) : Event

@Serializable
data class DirEntry(val name: String, val path: String, val isDir: Boolean)

/** Answer to [GetFile]. `error` non-null when the file can't be served. */
@Serializable
@SerialName("file_content")
data class FileContent(
    override val sessionId: String,
    val path: String,
    val content: String,
    val truncated: Boolean = false,
    val error: String? = null,
) : Event

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
    /** Unified diff when the tool changed a file (write/edit), for diff-review UI. */
    val diff: String? = null,
) : Event

/**
 * The core is blocked waiting for the user to approve a tool call.
 * Answered with [ApprovalResponse].
 */
/**
 * The agent is asking the user to choose. Answered with [ClarifyResponse].
 * The surface renders [options] (each label + description) plus a free-text
 * ("type something") and a "chat about this" affordance.
 */
@Serializable
@SerialName("clarify_request")
data class ClarifyRequest(
    override val sessionId: String,
    val clarifyId: String,
    val question: String,
    val options: List<ClarifyOption>,
) : Event

@Serializable
data class ClarifyOption(val label: String, val description: String = "")


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
