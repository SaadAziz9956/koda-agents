package dev.koda.cli

import dev.koda.core.KodaCore
import dev.koda.core.PermissionMode
import dev.koda.protocol.CompactSession
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Event
import dev.koda.protocol.ListMcpServers
import dev.koda.protocol.ListSessions
import dev.koda.protocol.McpServerList
import dev.koda.protocol.PermissionModeSetting
import dev.koda.protocol.SessionCompacted
import dev.koda.protocol.SessionList
import dev.koda.protocol.SetPermissionMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.channels.Channel

/**
 * The slash-command registry: one table of commands shared by every
 * interactive surface. Commands talk to the core only through the
 * protocol — same as any other client.
 */

class CliState(
    var sessionId: String,
    var mode: PermissionMode,
) {
    var contextLength: Long = 0
    var lastPromptTokens: Long = 0
    var turnCount: Int = 0

    val contextPercent: Int?
        get() = if (contextLength > 0 && lastPromptTokens > 0) {
            ((lastPromptTokens * 100) / contextLength).toInt().coerceAtMost(100)
        } else null
}

class CommandContext(
    val core: KodaCore,
    val inbox: Channel<Event>,
    val state: CliState,
)

class CliCommand(
    val name: String,
    val usage: String,
    val description: String,
    val run: suspend (CommandContext, String?) -> Unit,
)

private val timeFormat = DateTimeFormatter.ofPattern("MMM d HH:mm")

val COMMANDS: List<CliCommand> = listOf(
    CliCommand("help", "/help", "Show available commands") { _, _ ->
        COMMANDS.forEach { println("  ${BOLD}${it.usage.padEnd(18)}${RESET}${DIM}${it.description}${RESET}") }
    },

    CliCommand("new", "/new", "Start a fresh session") { ctx, _ ->
        ctx.state.sessionId = UUID.randomUUID().toString().take(8)
        ctx.state.lastPromptTokens = 0
        ctx.state.turnCount = 0
        println("${DIM}new session: ${ctx.state.sessionId}${RESET}")
    },

    CliCommand("sessions", "/sessions", "List persisted sessions") { ctx, _ ->
        ctx.core.submit(ListSessions(UUID.randomUUID().toString(), ctx.state.sessionId))
        val list = ctx.inbox.awaitFirst<SessionList>() ?: return@CliCommand
        if (list.sessions.isEmpty()) {
            println("${DIM}no persisted sessions${RESET}")
        } else {
            list.sessions.forEach { s ->
                val current = if (s.id == ctx.state.sessionId) " ${CYAN}(current)${RESET}" else ""
                val time = timeFormat.format(
                    Instant.ofEpochMilli(s.updatedAtEpochMs).atZone(ZoneId.systemDefault())
                )
                println("  ${BOLD}${s.id}${RESET}  ${DIM}$time  ${s.messageCount} messages${RESET}$current")
            }
        }
    },

    CliCommand("resume", "/resume <id>", "Switch to a persisted session") { ctx, arg ->
        if (arg.isNullOrBlank()) {
            println("${YELLOW}usage: /resume <session-id> — see /sessions${RESET}")
        } else {
            ctx.state.sessionId = arg.trim()
            ctx.state.lastPromptTokens = 0
            println("${DIM}resumed session: ${ctx.state.sessionId} (history loads on your next message)${RESET}")
        }
    },

    CliCommand("compact", "/compact", "Compress this session's history") { ctx, _ ->
        ctx.core.submit(CompactSession(UUID.randomUUID().toString(), ctx.state.sessionId))
        ctx.inbox.awaitFirst<SessionCompacted>()?.let {
            println("${DIM}compacted: ${it.tokensBefore} -> ${it.tokensAfter} tokens${RESET}")
        }
    },

    CliCommand("usage", "/usage", "Show context and turn stats") { ctx, _ ->
        val state = ctx.state
        val pct = state.contextPercent?.let { " ($it% of ${state.contextLength})" } ?: ""
        println("  ${DIM}session:${RESET} ${state.sessionId}")
        println("  ${DIM}turns:${RESET}   ${state.turnCount}")
        println("  ${DIM}context:${RESET} ${state.lastPromptTokens} tokens$pct")
        println("  ${DIM}mode:${RESET}    ${state.mode.name.lowercase()}")
    },

    CliCommand("mcp", "/mcp", "List connected MCP servers and their tools") { ctx, _ ->
        ctx.core.submit(ListMcpServers(UUID.randomUUID().toString(), ctx.state.sessionId))
        val list = ctx.inbox.awaitFirst<McpServerList>() ?: return@CliCommand
        if (list.servers.isEmpty()) {
            println("${DIM}no MCP servers connected — configure them in ~/.koda/mcp.json or .koda/mcp.json${RESET}")
        } else {
            list.servers.forEach { server ->
                println("  ${BOLD}${server.name}${RESET}  ${DIM}${server.toolNames.size} tools: ${server.toolNames.joinToString(", ").take(160)}${RESET}")
            }
        }
    },

    CliCommand("yolo", "/yolo", "Toggle approval-free mode for this session") { ctx, _ ->
        val newMode =
            if (ctx.state.mode == PermissionMode.YOLO) PermissionMode.DEFAULT else PermissionMode.YOLO
        ctx.state.mode = newMode
        ctx.core.submit(
            SetPermissionMode(
                UUID.randomUUID().toString(),
                ctx.state.sessionId,
                when (newMode) {
                    PermissionMode.DEFAULT -> PermissionModeSetting.DEFAULT
                    PermissionMode.ACCEPT_EDITS -> PermissionModeSetting.ACCEPT_EDITS
                    PermissionMode.YOLO -> PermissionModeSetting.YOLO
                },
            )
        )
        println("${DIM}permission mode: ${newMode.name.lowercase()}${RESET}")
    },
)

/** Returns true if the line was a slash command (known or not). */
suspend fun dispatchCommand(line: String, ctx: CommandContext): Boolean {
    if (!line.startsWith("/")) return false
    val parts = line.removePrefix("/").split(" ", limit = 2)
    val command = COMMANDS.firstOrNull { it.name == parts[0].lowercase() }
    if (command == null) {
        println("${YELLOW}unknown command: /${parts[0]} — try /help${RESET}")
    } else {
        command.run(ctx, parts.getOrNull(1))
    }
    return true
}

/** Drains the inbox until an event of type [T] or an [ErrorEvent] arrives. */
private suspend inline fun <reified T : Event> Channel<Event>.awaitFirst(): T? {
    while (true) {
        when (val event = receive()) {
            is T -> return event
            is ErrorEvent -> {
                println("${RED}error: ${event.message}${RESET}")
                return null
            }
            else -> {}
        }
    }
}
