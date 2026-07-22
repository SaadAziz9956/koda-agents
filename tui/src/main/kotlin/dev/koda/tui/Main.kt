package dev.koda.tui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.StaticEffect
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import dev.koda.client.StartupException
import dev.koda.client.resolveStartup
import dev.koda.core.KodaCore
import dev.koda.core.PermissionMode
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.CompactSession
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Interrupt
import dev.koda.protocol.ListMcpServers
import dev.koda.protocol.ListSessions
import dev.koda.protocol.ListSkills
import dev.koda.protocol.McpServerList
import dev.koda.protocol.Notice
import dev.koda.protocol.PermissionModeSetting
import dev.koda.protocol.SessionCompacted
import dev.koda.protocol.SessionList
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.SetPermissionMode
import dev.koda.protocol.SkillList
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStarted
import dev.koda.protocol.TurnStopReason
import dev.koda.protocol.UserTurn
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Koda's Mosaic TUI surface — a thin client of [KodaCore] over the protocol.
 * Finished transcript items scroll into terminal history via [StaticEffect];
 * a slash-command palette, a full-width input box (block cursor + line
 * editing), and a status footer stay pinned in the live frame. Renders only
 * in a real terminal (Mosaic repaints via ANSI, which IDE consoles strip).
 */
fun main(args: Array<String>) {
    val startup = try {
        resolveStartup(args)
    } catch (e: StartupException) {
        System.err.println("koda: ${e.message}")
        kotlin.system.exitProcess(1)
    }
    val config = startup.config

    KodaCore.create(config).use { core ->
        core.start()
        runMosaicBlocking {
            KodaApp(core, startup.sessionId, config.model, config.provider.name, config.cwd.toString(), config.permissionMode)
        }
    }
}

private val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

private data class TuiCmd(val name: String, val desc: String)

private val COMMANDS = listOf(
    TuiCmd("help", "show available commands"),
    TuiCmd("new", "start a fresh session"),
    TuiCmd("resume", "switch to a saved session: /resume <id>"),
    TuiCmd("sessions", "list saved sessions"),
    TuiCmd("clear", "clear the screen"),
    TuiCmd("compact", "compress conversation history"),
    TuiCmd("rewind", "undo the last n turns: /rewind [n]"),
    TuiCmd("skills", "list available skills"),
    TuiCmd("mcp", "list connected MCP servers"),
    TuiCmd("model", "show the current model"),
    TuiCmd("yolo", "toggle approval-free mode"),
    TuiCmd("exit", "quit koda"),
)

private sealed interface Item {
    val n: Int
    data class User(override val n: Int, val text: String) : Item
    data class Assistant(override val n: Int, val text: String) : Item
    data class Tool(override val n: Int, val text: String, val error: Boolean) : Item
    data class Note(override val n: Int, val text: String, val color: Color) : Item
}

@Composable
private fun KodaApp(
    core: KodaCore,
    initialSessionId: String,
    model: String,
    provider: String,
    cwd: String,
    initialMode: PermissionMode,
) {
    val scope = rememberCoroutineScope()
    var sessionId by remember { mutableStateOf(initialSessionId) }
    val transcript = remember { mutableStateListOf<Item>() }
    var seq by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    // Live assistant text for the in-flight iteration: accumulates TextDelta
    // chunks and renders in the mutable frame, then commits to the static
    // transcript as a finished item when AssistantMessage arrives.
    var streaming by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ApprovalRequest?>(null) }
    var contextLength by remember { mutableStateOf(0L) }
    var usedTokens by remember { mutableStateOf(0L) }
    var mode by remember { mutableStateOf(initialMode) }
    val history = remember { mutableStateListOf<String>() }
    var historyIdx by remember { mutableStateOf(0) }
    var paletteIdx by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }

    fun add(item: Item) { transcript.add(item); seq++ }
    fun id() = UUID.randomUUID().toString()

    LaunchedEffect(Unit) { while (true) { delay(110); tick++ } }

    LaunchedEffect(Unit) {
        core.events.collect { ev ->
            when (ev) {
                is SessionStarted -> if (ev.sessionId == sessionId) contextLength = ev.contextLength
                is TurnStarted -> if (ev.sessionId == sessionId) streaming = ""
                is TextDelta -> if (ev.sessionId == sessionId) streaming += ev.text
                is AssistantMessage -> {
                    // Commit the finished text and drop the live buffer in one
                    // recomposition — no flicker between the two.
                    if (ev.text.isNotBlank()) add(Item.Assistant(seq, ev.text))
                    streaming = ""
                }
                is ToolBegin -> { streaming = ""; add(Item.Tool(seq, "⚙ ${ev.toolName} ${ev.argsJson.take(120)}", false)) }
                is ToolEnd -> {
                    val mark = if (ev.isError) "✗" else "✓"
                    add(Item.Tool(seq, "$mark ${ev.toolName}: ${ev.output.lineSequence().firstOrNull()?.take(110) ?: ""}", ev.isError))
                }
                is ApprovalRequest -> pending = ev
                is TokenUsage -> if (ev.sessionId == sessionId) usedTokens = ev.inputTokens
                is Notice -> add(Item.Note(seq, ev.text, KodaColors.dim))
                is SessionCompacted -> add(Item.Note(seq, "compacted: ${ev.messagesBefore} → ${ev.messagesAfter} messages", KodaColors.dim))
                is dev.koda.protocol.RewindResult -> if (ev.sessionId == sessionId) add(Item.Note(seq, "⏪ ${ev.message}", if (ev.ok) KodaColors.dim else KodaColors.warn))
                is ErrorEvent -> add(Item.Note(seq, "error: ${ev.message}", KodaColors.err))
                is SessionList -> add(Item.Note(seq, "sessions:\n" + (ev.sessions.joinToString("\n") { "  ${it.id}  ·  ${it.messageCount} msgs" }.ifEmpty { "  (none)" }), KodaColors.dim))
                is SkillList -> add(Item.Note(seq, "skills (${ev.skills.size}): " + ev.skills.take(40).joinToString(", ") { it.name }, KodaColors.dim))
                is dev.koda.protocol.SessionHistory -> if (ev.sessionId == sessionId) {
                    transcript.clear(); seq++
                    ev.messages.forEach { m ->
                        if (m.role == "user") add(Item.User(seq, m.text)) else add(Item.Assistant(seq, m.text))
                    }
                    add(Item.Note(seq, "— resumed $sessionId (${ev.messages.size} messages) —", KodaColors.dim))
                }
                is McpServerList -> add(Item.Note(seq, if (ev.servers.isEmpty()) "no MCP servers connected" else "mcp: " + ev.servers.joinToString(", ") { "${it.name}(${it.toolNames.size})" }, KodaColors.dim))
                is TurnCompleted -> {
                    working = false
                    streaming = ""
                    when (ev.stopReason) {
                        TurnStopReason.MAX_ITERATIONS -> add(Item.Note(seq, "(stopped: max iterations)", KodaColors.warn))
                        TurnStopReason.INTERRUPTED -> add(Item.Note(seq, "(interrupted)", KodaColors.warn))
                        else -> {}
                    }
                }
                else -> {}
            }
        }
    }

    fun answer(decision: ApprovalDecision) {
        val p = pending ?: return
        pending = null
        scope.launch { core.submit(ApprovalResponse(id(), sessionId, p.approvalId, decision)) }
    }

    fun runCommand(name: String, arg: String? = null) {
        when (name) {
            "help" -> add(Item.Note(seq, "commands:\n" + COMMANDS.joinToString("\n") { "  /${it.name.padEnd(9)} ${it.desc}" }, KodaColors.dim))
            "new" -> {
                sessionId = id().take(8); transcript.clear(); seq++; usedTokens = 0
                add(Item.Note(seq, "new session: $sessionId", KodaColors.dim))
            }
            "resume" -> {
                val target = arg?.trim()
                if (target.isNullOrEmpty()) {
                    add(Item.Note(seq, "usage: /resume <id> — see /sessions", KodaColors.warn))
                } else {
                    sessionId = target; usedTokens = 0
                    scope.launch { core.submit(dev.koda.protocol.LoadHistory(id(), target)) }
                }
            }
            "clear" -> { transcript.clear(); seq++ }
            "compact" -> scope.launch { core.submit(CompactSession(id(), sessionId)) }
            "rewind" -> {
                val steps = arg?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                scope.launch { core.submit(dev.koda.protocol.Rewind(id(), sessionId, steps)) }
            }
            "sessions" -> scope.launch { core.submit(ListSessions(id(), sessionId)) }
            "skills" -> scope.launch { core.submit(ListSkills(id(), sessionId)) }
            "mcp" -> scope.launch { core.submit(ListMcpServers(id(), sessionId)) }
            "model" -> add(Item.Note(seq, "model: $model · $provider", KodaColors.dim))
            "yolo" -> {
                mode = if (mode == PermissionMode.YOLO) PermissionMode.DEFAULT else PermissionMode.YOLO
                val setting = when (mode) {
                    PermissionMode.YOLO -> PermissionModeSetting.YOLO
                    PermissionMode.ACCEPT_EDITS -> PermissionModeSetting.ACCEPT_EDITS
                    PermissionMode.DEFAULT -> PermissionModeSetting.DEFAULT
                }
                scope.launch { core.submit(SetPermissionMode(id(), sessionId, setting)) }
                add(Item.Note(seq, "permission mode: ${mode.name.lowercase()}", KodaColors.dim))
            }
            "exit" -> kotlin.system.exitProcess(0)
            else -> add(Item.Note(seq, "unknown command: /$name — type / for the list", KodaColors.warn))
        }
    }

    // Slash-command palette state, derived from the input buffer.
    val cmdQuery = if (input.startsWith("/")) input.drop(1).substringBefore(' ').lowercase() else ""
    val filtered = if (input.startsWith("/") && !input.drop(1).contains(' ')) COMMANDS.filter { it.name.startsWith(cmdQuery) } else emptyList()
    val paletteOpen = filtered.isNotEmpty() && pending == null
    val palIdx = paletteIdx.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))

    // Welcome banner + committed transcript scroll into terminal history.
    StaticEffect { WelcomeBanner(model, provider, cwd, sessionId) }
    for (item in transcript) key(item.n) { StaticEffect { ItemView(item) } }

    // Real terminal width: read once from the TTY (Mosaic can under-report), and
    // also honor Mosaic's live value so growing the window still widens the box.
    val ttyCols = remember { ttyColumns() ?: 0 }
    val width = maxOf(LocalTerminalState.current.size.width, ttyCols).let { if (it <= 0) 80 else it }
    val blinkOn = (tick / 5) % 2 == 0

    Column(
        modifier = Modifier.onKeyEvent { e ->
            if (e.ctrl && e.key == "c") {
                if (working) scope.launch { core.submit(Interrupt(id(), sessionId)) } else kotlin.system.exitProcess(0)
                return@onKeyEvent true
            }
            if (pending != null) {
                when (e.key.lowercase()) {
                    "y" -> answer(ApprovalDecision.APPROVE)
                    "a" -> answer(ApprovalDecision.APPROVE_ALWAYS)
                    "n" -> answer(ApprovalDecision.DENY)
                }
                return@onKeyEvent true
            }
            // Palette navigation intercepts arrows/Tab/Enter/Esc while open.
            if (paletteOpen) {
                when (e.key) {
                    "ArrowDown" -> { paletteIdx = (palIdx + 1) % filtered.size; return@onKeyEvent true }
                    "ArrowUp" -> { paletteIdx = (palIdx - 1 + filtered.size) % filtered.size; return@onKeyEvent true }
                    "Tab" -> { input = "/" + filtered[palIdx].name + " "; cursor = input.length; paletteIdx = 0; return@onKeyEvent true }
                    "Enter" -> { input = ""; cursor = 0; paletteIdx = 0; runCommand(filtered[palIdx].name); return@onKeyEvent true }
                    "Escape" -> { input = ""; cursor = 0; return@onKeyEvent true }
                    else -> {}
                }
            }
            when (e.key) {
                "Enter" -> {
                    // Shift/Alt+Enter inserts a newline; plain Enter submits.
                    if (e.shift || e.alt) {
                        input = input.substring(0, cursor) + "\n" + input.substring(cursor); cursor++
                    } else {
                        val text = input.trim(); input = ""; cursor = 0
                        when {
                            text.isEmpty() -> {}
                            text == "/exit" || text == "/quit" || text == "exit" -> kotlin.system.exitProcess(0)
                            text.startsWith("/") -> {
                            val parts = text.drop(1).split(" ", limit = 2)
                            runCommand(parts[0].lowercase(), parts.getOrNull(1))
                        }
                            else -> {
                                add(Item.User(seq, text))
                                history.add(text); historyIdx = history.size
                                working = true
                                scope.launch { core.submit(UserTurn(id(), sessionId, text)) }
                            }
                        }
                    }
                }
                "Backspace" -> if (cursor > 0) { input = input.removeRange(cursor - 1, cursor); cursor--; paletteIdx = 0 }
                "Delete" -> if (cursor < input.length) input = input.removeRange(cursor, cursor + 1)
                "ArrowLeft" -> cursor = (cursor - 1).coerceAtLeast(0)
                "ArrowRight" -> cursor = (cursor + 1).coerceAtMost(input.length)
                "Home" -> cursor = 0
                "End" -> cursor = input.length
                "ArrowUp" -> if (history.isNotEmpty()) { historyIdx = (historyIdx - 1).coerceAtLeast(0); input = history[historyIdx]; cursor = input.length }
                "ArrowDown" -> { historyIdx = (historyIdx + 1).coerceAtMost(history.size); input = if (historyIdx >= history.size) "" else history[historyIdx]; cursor = input.length }
                " " -> { input = input.substring(0, cursor) + " " + input.substring(cursor); cursor++ }
                else -> if (e.key.length == 1) { input = input.substring(0, cursor) + e.key + input.substring(cursor); cursor++; paletteIdx = 0 }
            }
            true
        },
    ) {
        Text("")
        // The in-flight assistant message, streamed token-by-token. Lives in the
        // mutable frame until AssistantMessage commits it to the static transcript.
        if (streaming.isNotBlank()) {
            MarkdownText(streaming)
            Text("")
        }
        val prompt = pending
        if (prompt != null) {
            // Approval: options must always be visible — truncate the command, never the choices.
            Text("  ⚠ approval needed", color = KodaColors.warn, textStyle = TextStyle.Bold)
            Text("    ${prompt.summary.take((width - 6).coerceAtLeast(20))}", color = KodaColors.warn)
            Text("    [y] allow    [a] always    [n] deny", color = KodaColors.warn, textStyle = TextStyle.Bold)
        } else {
            if (paletteOpen) {
                filtered.forEachIndexed { i, c ->
                    val sel = i == palIdx
                    Text(
                        (if (sel) "❯ " else "  ") + "/${c.name.padEnd(9)} ${c.desc}",
                        color = if (sel) KodaColors.accent else KodaColors.dim,
                        textStyle = if (sel) TextStyle.Bold else TextStyle.Unspecified,
                    )
                }
            }
            Text(border(width, top = true), color = KodaColors.border)
            for (row in composerRows(width, input, cursor, blinkOn)) Text(row)
            Text(border(width, top = false), color = KodaColors.border)
        }
        Text(footer(model, contextLength, usedTokens, working, mode, SPINNER[tick % SPINNER.length]), color = KodaColors.dim)
    }
}

@Composable
private fun ItemView(item: Item) {
    when (item) {
        is Item.User -> Text("❯ " + item.text, color = KodaColors.accent, textStyle = TextStyle.Bold)
        is Item.Assistant -> MarkdownText(item.text)
        is Item.Tool -> Text("  " + item.text, color = if (item.error) KodaColors.err else KodaColors.tool)
        is Item.Note -> Text("  " + item.text, color = item.color)
    }
}

@Composable
private fun WelcomeBanner(model: String, provider: String, cwd: String, sessionId: String) {
    val lines = listOf(
        "koda",
        "$model · $provider · session $sessionId",
        cwd,
        "type a message · / for commands · Ctrl-C interrupts",
    )
    val w = lines.maxOf { it.length } + 2
    Column {
        Text("╭" + "─".repeat(w) + "╮", color = KodaColors.accent)
        lines.forEachIndexed { i, text ->
            Text(
                "│ " + text.padEnd(w - 1) + "│",
                color = if (i == 0) KodaColors.accent else KodaColors.dim,
                textStyle = if (i == 0) TextStyle.Bold else TextStyle.Unspecified,
            )
        }
        Text("╰" + "─".repeat(w) + "╯", color = KodaColors.accent)
    }
}

private fun border(width: Int, top: Boolean): String {
    val (l, r) = if (top) "╭" to "╮" else "╰" to "╯"
    return l + "─".repeat((width - 2).coerceAtLeast(0)) + r
}

/**
 * The composer rendered as one or more full-width box rows: "❯ " + input,
 * soft-wrapped at the inner width, with a block cursor at its position. The
 * box grows in height as the text wraps to more lines.
 */
private fun composerRows(width: Int, input: String, cursor: Int, blinkOn: Boolean): List<com.jakewharton.mosaic.text.AnnotatedString> {
    val inner = (width - 4).coerceAtLeast(8)
    val full = "❯ $input"
    val cursorAt = 2 + cursor

    // Walk chars into rows, breaking on '\n' (hard newline) and at the inner width
    // (soft wrap). Each cell keeps its global index so we can place the cursor and
    // style the "❯ " prompt.
    data class Cell(val ch: Char, val idx: Int)
    val rows = ArrayList<MutableList<Cell>>()
    var row = ArrayList<Cell>()
    for (idx in full.indices) {
        val c = full[idx]
        if (c == '\n') { rows.add(row); row = ArrayList(); continue }
        if (row.size >= inner) { rows.add(row); row = ArrayList() }
        row.add(Cell(c, idx))
    }
    rows.add(row)

    return rows.map { cells ->
        buildAnnotatedString {
            append("│ ")
            for (col in 0 until inner) {
                val cell = cells.getOrNull(col)
                val ch = cell?.ch ?: ' '
                val here = cell?.idx ?: (cells.lastOrNull()?.idx?.plus(1) ?: 0)
                when {
                    blinkOn && here == cursorAt && cell != null -> { pushStyle(SpanStyle(textStyle = TextStyle.Invert)); append(ch); pop() }
                    blinkOn && cursorAt == full.length && cells === rows.last() && col == cells.size -> { pushStyle(SpanStyle(textStyle = TextStyle.Invert)); append(' '); pop() }
                    cell != null && cell.idx < 2 -> { pushStyle(SpanStyle(color = KodaColors.accent, textStyle = TextStyle.Bold)); append(ch); pop() }
                    else -> append(ch)
                }
            }
            append(" │")
        }
    }
}

/** True terminal column count from the controlling TTY, or null if unavailable. */
private fun ttyColumns(): Int? = runCatching {
    val p = ProcessBuilder("sh", "-c", "stty size < /dev/tty 2>/dev/null").redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    p.waitFor()
    out.split(" ").getOrNull(1)?.toIntOrNull()
}.getOrNull()

private fun footer(model: String, contextLength: Long, used: Long, working: Boolean, mode: PermissionMode, spin: Char): String {
    val pct = if (contextLength > 0 && used > 0) "${(used * 100 / contextLength).coerceAtMost(100)}%" else "—"
    val lead = if (working) "$spin working · " else ""
    val modeTag = if (mode == PermissionMode.YOLO) " · yolo" else ""
    return "  $lead$model · context $pct$modeTag · / for commands"
}
