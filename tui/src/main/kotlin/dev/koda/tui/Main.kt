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
import dev.koda.core.ApiShape
import dev.koda.core.KodaConfig
import dev.koda.core.KodaCore
import dev.koda.core.PermissionMode
import dev.koda.core.ProviderConfig
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
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStopReason
import dev.koda.protocol.UserTurn
import java.nio.file.Path
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
    val provider = resolveProvider(args)
    val model = args.valueOf("--model") ?: defaultModel(provider)
    val config = KodaConfig(
        provider = provider,
        model = model,
        cwd = Path.of(System.getProperty("user.dir")),
        permissionMode = if ("--yolo" in args) PermissionMode.YOLO else PermissionMode.DEFAULT,
    )
    val sessionId = args.valueOf("--session") ?: UUID.randomUUID().toString().take(8)

    KodaCore.create(config).use { core ->
        core.start()
        runMosaicBlocking {
            KodaApp(core, sessionId, model, provider.name, config.cwd.toString(), config.permissionMode)
        }
    }
}

private val SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"

private data class TuiCmd(val name: String, val desc: String)

private val COMMANDS = listOf(
    TuiCmd("help", "show available commands"),
    TuiCmd("clear", "clear the screen"),
    TuiCmd("compact", "compress conversation history"),
    TuiCmd("sessions", "list saved sessions"),
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
    sessionId: String,
    model: String,
    provider: String,
    cwd: String,
    initialMode: PermissionMode,
) {
    val scope = rememberCoroutineScope()
    val transcript = remember { mutableStateListOf<Item>() }
    var seq by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
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
                is AssistantMessage -> if (ev.text.isNotBlank()) add(Item.Assistant(seq, ev.text))
                is ToolBegin -> add(Item.Tool(seq, "⚙ ${ev.toolName} ${ev.argsJson.take(120)}", false))
                is ToolEnd -> {
                    val mark = if (ev.isError) "✗" else "✓"
                    add(Item.Tool(seq, "$mark ${ev.toolName}: ${ev.output.lineSequence().firstOrNull()?.take(110) ?: ""}", ev.isError))
                }
                is ApprovalRequest -> pending = ev
                is TokenUsage -> if (ev.sessionId == sessionId) usedTokens = ev.inputTokens
                is Notice -> add(Item.Note(seq, ev.text, KodaColors.dim))
                is SessionCompacted -> add(Item.Note(seq, "compacted: ${ev.messagesBefore} → ${ev.messagesAfter} messages", KodaColors.dim))
                is ErrorEvent -> add(Item.Note(seq, "error: ${ev.message}", KodaColors.err))
                is SessionList -> add(Item.Note(seq, "sessions:\n" + (ev.sessions.joinToString("\n") { "  ${it.id}  ·  ${it.messageCount} msgs" }.ifEmpty { "  (none)" }), KodaColors.dim))
                is SkillList -> add(Item.Note(seq, "skills (${ev.skills.size}): " + ev.skills.take(40).joinToString(", ") { it.name }, KodaColors.dim))
                is McpServerList -> add(Item.Note(seq, if (ev.servers.isEmpty()) "no MCP servers connected" else "mcp: " + ev.servers.joinToString(", ") { "${it.name}(${it.toolNames.size})" }, KodaColors.dim))
                is TurnCompleted -> {
                    working = false
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

    fun runCommand(name: String) {
        when (name) {
            "help" -> add(Item.Note(seq, "commands:\n" + COMMANDS.joinToString("\n") { "  /${it.name.padEnd(9)} ${it.desc}" }, KodaColors.dim))
            "clear" -> { transcript.clear(); seq++ }
            "compact" -> scope.launch { core.submit(CompactSession(id(), sessionId)) }
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

    // Fall back to 80 when the terminal size isn't reported (Mosaic can return 0/tiny early).
    val width = LocalTerminalState.current.size.width.let { if (it < 40) 80 else it }.coerceAtMost(200)
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
                    val text = input.trim(); input = ""; cursor = 0
                    when {
                        text.isEmpty() -> {}
                        text == "/exit" || text == "/quit" || text == "exit" -> kotlin.system.exitProcess(0)
                        text.startsWith("/") -> runCommand(text.drop(1).substringBefore(' ').lowercase())
                        else -> {
                            add(Item.User(seq, text))
                            history.add(text); historyIdx = history.size
                            working = true
                            scope.launch { core.submit(UserTurn(id(), sessionId, text)) }
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
            Text(composerLine(width, input, cursor, blinkOn))
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

private fun composerLine(width: Int, input: String, cursor: Int, blinkOn: Boolean) = buildAnnotatedString {
    val inner = (width - 4).coerceAtLeast(8)
    val full = "❯ $input"
    val cursorAt = 2 + cursor
    val start = (cursorAt - inner + 1).coerceAtLeast(0)
    val visible = full.substring(start, minOf(full.length, start + inner))
    val cursorIdx = cursorAt - start

    append("│ ")
    for (i in 0 until inner) {
        val ch = if (i < visible.length) visible[i] else ' '
        when {
            i == cursorIdx && blinkOn -> { pushStyle(SpanStyle(textStyle = TextStyle.Invert)); append(ch); pop() }
            i < 2 -> { pushStyle(SpanStyle(color = KodaColors.accent, textStyle = TextStyle.Bold)); append(ch); pop() }
            else -> append(ch)
        }
    }
    append(" │")
}

private fun boxLine(width: Int, text: String): String {
    val inner = (width - 4).coerceAtLeast(8)
    val clipped = if (text.length > inner) text.take(inner - 1) + "…" else text.padEnd(inner)
    return "│ $clipped │"
}

private fun footer(model: String, contextLength: Long, used: Long, working: Boolean, mode: PermissionMode, spin: Char): String {
    val pct = if (contextLength > 0 && used > 0) "${(used * 100 / contextLength).coerceAtMost(100)}%" else "—"
    val lead = if (working) "$spin working · " else ""
    val modeTag = if (mode == PermissionMode.YOLO) " · yolo" else ""
    return "  $lead$model · context $pct$modeTag · / for commands"
}

// --- provider resolution (shared client module comes in a later increment) ---

private fun Array<String>.valueOf(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

private fun resolveProvider(args: Array<String>): ProviderConfig {
    val anthropic = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    val openai = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    val baseUrl = args.valueOf("--base-url") ?: System.getenv("KODA_BASE_URL")
    return when {
        args.valueOf("--provider") == "custom" || baseUrl != null -> ProviderConfig(
            "custom", baseUrl ?: error("--base-url required for custom provider"),
            System.getenv("KODA_API_KEY") ?: openai ?: "", ApiShape.OPENAI_CHAT_COMPLETIONS,
        )
        anthropic != null -> ProviderConfig("anthropic", "https://api.anthropic.com", anthropic, ApiShape.ANTHROPIC_MESSAGES)
        openai != null -> ProviderConfig("openai", "https://api.openai.com/v1", openai, ApiShape.OPENAI_CHAT_COMPLETIONS)
        else -> { System.err.println("koda: set ANTHROPIC_API_KEY or OPENAI_API_KEY"); kotlin.system.exitProcess(1) }
    }
}

private fun defaultModel(p: ProviderConfig) =
    if (p.apiShape == ApiShape.ANTHROPIC_MESSAGES) "claude-sonnet-4-6" else "gpt-5.1"
