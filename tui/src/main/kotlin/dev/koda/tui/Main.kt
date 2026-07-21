package dev.koda.tui

import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.TextColors.brightCyan
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightRed
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.terminal.Terminal
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
import dev.koda.protocol.Event
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import sun.misc.Signal

/**
 * Koda's terminal UI surface. A thin client of [KodaCore] over the protocol —
 * same boundary the CLI uses — with Mordant handling rendering: assistant
 * replies as formatted Markdown, styled tool activity, and a status line.
 */
private val t = Terminal()

fun main(args: Array<String>) {
    val provider = resolveProvider(args)
    val model = args.modelArg() ?: defaultModel(provider)
    val config = KodaConfig(
        provider = provider,
        model = model,
        cwd = Path.of(System.getProperty("user.dir")),
        permissionMode = if ("--yolo" in args) PermissionMode.YOLO else PermissionMode.DEFAULT,
    )

    KodaCore.create(config).use { core ->
        runBlocking {
            val coreJob = core.start()
            val inbox = Channel<Event>(Channel.UNLIMITED)
            val collector = launch { core.events.collect { inbox.send(it) } }
            val sessionId = args.sessionArg() ?: UUID.randomUUID().toString().take(8)
            val status = Status(model, provider.name)

            val turnActive = AtomicBoolean(false)
            Signal.handle(Signal("INT")) {
                if (turnActive.get()) runBlocking { core.submit(Interrupt(UUID.randomUUID().toString(), sessionId)) }
                else kotlin.system.exitProcess(130)
            }

            t.println((bold + brightCyan)("  koda ") + dim("· ${config.model} · ${provider.name} · session $sessionId"))
            t.println(dim("  type a message · /exit to quit · Ctrl-C interrupts a turn"))

            while (true) {
                t.print("\n" + status.line() + "\n" + (bold + brightCyan)("❯ "))
                System.out.flush()
                val line = readlnOrNull()?.trim() ?: break
                if (line.isEmpty()) continue
                if (line == "/exit" || line == "/quit" || line == "exit") break

                turnActive.set(true)
                core.submit(UserTurn(UUID.randomUUID().toString(), sessionId, line))
                consumeTurn(core, inbox, sessionId, status)
                turnActive.set(false)
            }
            collector.cancel(); coreJob.cancel()
        }
    }
}

/** One turn: render events until TurnCompleted for this session. */
private suspend fun consumeTurn(core: KodaCore, inbox: Channel<Event>, sessionId: String, status: Status) {
    while (true) {
        when (val e = inbox.receive()) {
            is SessionStarted -> if (e.sessionId == sessionId) status.contextLength = e.contextLength
            is AssistantMessage -> if (e.text.isNotBlank()) t.println(Markdown(e.text))
            is ToolBegin -> t.println(gray("  ⚙ ${e.toolName} ${e.argsJson.take(140)}"))
            is ToolEnd -> {
                val mark = if (e.isError) brightRed("✗") else brightGreen("✓")
                t.println(gray("  $mark ${e.toolName}: ") + dim(e.output.lineSequence().firstOrNull()?.take(120) ?: ""))
            }
            is ApprovalRequest -> core.submit(
                ApprovalResponse(UUID.randomUUID().toString(), sessionId, e.approvalId, promptApproval(e))
            )
            is TokenUsage -> if (e.sessionId == sessionId) status.usedTokens = e.inputTokens
            is SessionCompacted -> t.println(dim("  compacted: ${e.messagesBefore} → ${e.messagesAfter} messages"))
            is Notice -> t.println(dim("  ${e.text}"))
            is ErrorEvent -> t.println(brightRed("  error: ${e.message}"))
            is TurnCompleted -> {
                when (e.stopReason) {
                    TurnStopReason.MAX_ITERATIONS -> t.println(brightYellow("  (stopped: max iterations)"))
                    TurnStopReason.INTERRUPTED -> t.println(brightYellow("  (interrupted)"))
                    else -> {}
                }
                return
            }
            else -> {}
        }
    }
}

private fun promptApproval(request: ApprovalRequest): ApprovalDecision {
    t.println("\n" + (bold + brightYellow)("  approval ") + brightYellow(request.summary))
    t.print(brightYellow("  [y] allow  [a] always  [n] deny ❯ "))
    System.out.flush()
    return when (readlnOrNull()?.trim()?.lowercase()) {
        "y", "yes" -> ApprovalDecision.APPROVE
        "a", "always" -> ApprovalDecision.APPROVE_ALWAYS
        else -> ApprovalDecision.DENY
    }
}

/** The bottom status line: model, session token fill. */
private class Status(val model: String, val provider: String) {
    var contextLength: Long = 0
    var usedTokens: Long = 0
    fun line(): String {
        val pct = if (contextLength > 0 && usedTokens > 0)
            "${((usedTokens * 100) / contextLength).coerceAtMost(100)}%" else "—"
        return dim("  ┄ $model · context $pct ┄")
    }
}

// --- minimal env/arg provider resolution (shared client module comes later) ---

private fun Array<String>.valueOf(flag: String): String? =
    indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

private fun Array<String>.modelArg() = valueOf("--model")
private fun Array<String>.sessionArg() = valueOf("--session")

private fun resolveProvider(args: Array<String>): ProviderConfig {
    val anthropic = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
    val openai = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
    val baseUrl = args.valueOf("--base-url") ?: System.getenv("KODA_BASE_URL")
    return when {
        args.valueOf("--provider") == "custom" || baseUrl != null -> ProviderConfig(
            "custom",
            baseUrl ?: error("--base-url or KODA_BASE_URL required for custom provider"),
            System.getenv("KODA_API_KEY") ?: openai ?: "",
            ApiShape.OPENAI_CHAT_COMPLETIONS,
        )
        anthropic != null -> ProviderConfig("anthropic", "https://api.anthropic.com", anthropic, ApiShape.ANTHROPIC_MESSAGES)
        openai != null -> ProviderConfig("openai", "https://api.openai.com/v1", openai, ApiShape.OPENAI_CHAT_COMPLETIONS)
        else -> {
            System.err.println("koda: set ANTHROPIC_API_KEY or OPENAI_API_KEY (or --base-url for a custom endpoint)")
            kotlin.system.exitProcess(1)
        }
    }
}

private fun defaultModel(provider: ProviderConfig): String =
    if (provider.apiShape == ApiShape.ANTHROPIC_MESSAGES) "claude-sonnet-4-6" else "gpt-5.1"
