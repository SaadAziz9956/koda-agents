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
import com.jakewharton.mosaic.LocalStaticLogger
import com.jakewharton.mosaic.StaticEffect
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicBlocking
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
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Interrupt
import dev.koda.protocol.Notice
import dev.koda.protocol.SessionCompacted
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStopReason
import dev.koda.protocol.UserTurn
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Koda's Mosaic TUI surface. A thin client of [KodaCore] over the protocol:
 * finished transcript items scroll into history via [StaticEffect]; a status
 * line and input composer stay pinned in the live frame. Renders only in a
 * real terminal (Mosaic repaints via ANSI, which IDE run consoles strip).
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
        runMosaicBlocking { KodaApp(core, sessionId, model, provider.name, config.cwd.toString()) }
    }
}

private sealed interface Item {
    val n: Int
    data class User(override val n: Int, val text: String) : Item
    data class Assistant(override val n: Int, val text: String) : Item
    data class Tool(override val n: Int, val text: String, val error: Boolean) : Item
    data class Note(override val n: Int, val text: String, val color: Color) : Item
}

@Composable
private fun KodaApp(core: KodaCore, sessionId: String, model: String, provider: String, cwd: String) {
    val scope = rememberCoroutineScope()
    val transcript = remember { mutableStateListOf<Item>() }
    var seq by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ApprovalRequest?>(null) }
    var contextLength by remember { mutableStateOf(0L) }
    var usedTokens by remember { mutableStateOf(0L) }
    val history = remember { mutableStateListOf<String>() }
    var historyIdx by remember { mutableStateOf(-1) }

    fun add(item: Item) { transcript.add(item); seq++ }

    LaunchedEffect(Unit) {
        core.events.collect { ev ->
            when (ev) {
                is SessionStarted -> if (ev.sessionId == sessionId) contextLength = ev.contextLength
                is AssistantMessage -> if (ev.text.isNotBlank()) add(Item.Assistant(seq, ev.text))
                is ToolBegin -> add(Item.Tool(seq, "⚙ ${ev.toolName} ${ev.argsJson.take(120)}", false))
                is ToolEnd -> {
                    val mark = if (ev.isError) "✗" else "✓"
                    val preview = ev.output.lineSequence().firstOrNull()?.take(110) ?: ""
                    add(Item.Tool(seq, "$mark ${ev.toolName}: $preview", ev.isError))
                }
                is ApprovalRequest -> pending = ev
                is TokenUsage -> if (ev.sessionId == sessionId) usedTokens = ev.inputTokens
                is Notice -> add(Item.Note(seq, ev.text, KodaColors.dim))
                is SessionCompacted -> add(Item.Note(seq, "compacted: ${ev.messagesBefore} → ${ev.messagesAfter} messages", KodaColors.dim))
                is ErrorEvent -> add(Item.Note(seq, "error: ${ev.message}", KodaColors.err))
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
        scope.launch { core.submit(ApprovalResponse(UUID.randomUUID().toString(), sessionId, p.approvalId, decision)) }
    }

    // Welcome banner + committed transcript scroll into terminal history (each renders once).
    StaticEffect { WelcomeBanner(model, provider, cwd, sessionId) }
    for (item in transcript) {
        key(item.n) { StaticEffect { ItemView(item) } }
    }

    Column(
        modifier = Modifier.onKeyEvent { e ->
            // Ctrl-C: interrupt a running turn, else quit.
            if (e.ctrl && e.key == "c") {
                if (working) scope.launch { core.submit(Interrupt(UUID.randomUUID().toString(), sessionId)) }
                else kotlin.system.exitProcess(0)
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
            when (e.key) {
                "Enter" -> {
                    val text = input.trim()
                    input = ""
                    when {
                        text.isEmpty() -> {}
                        text == "/exit" || text == "/quit" || text == "exit" -> kotlin.system.exitProcess(0)
                        else -> {
                            add(Item.User(seq, text))
                            history.add(text); historyIdx = history.size
                            working = true
                            scope.launch { core.submit(UserTurn(UUID.randomUUID().toString(), sessionId, text)) }
                        }
                    }
                }
                "Backspace" -> input = input.dropLast(1)
                "ArrowUp" -> if (history.isNotEmpty()) {
                    historyIdx = (historyIdx - 1).coerceAtLeast(0); input = history[historyIdx]
                }
                "ArrowDown" -> {
                    historyIdx += 1
                    input = if (historyIdx >= history.size) { historyIdx = history.size; "" } else history[historyIdx]
                }
                " " -> input += " "
                else -> if (e.key.length == 1) input += e.key
            }
            true
        }
    ) {
        Text(statusLine(model, contextLength, usedTokens), color = KodaColors.dim)
        val prompt = pending
        when {
            prompt != null -> Text(
                "approval  ${prompt.summary}   [y] allow  [a] always  [n] deny",
                color = KodaColors.warn, textStyle = TextStyle.Bold,
            )
            working -> Text("❯ $input ⋯", color = KodaColors.accent)
            else -> Text("❯ $input", color = KodaColors.accent, textStyle = TextStyle.Bold)
        }
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
        "type a message · Ctrl-C interrupts · /exit to quit",
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

private fun statusLine(model: String, contextLength: Long, used: Long): String {
    val pct = if (contextLength > 0 && used > 0) "${(used * 100 / contextLength).coerceAtMost(100)}%" else "—"
    return "  ┄ $model · context $pct ┄"
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
