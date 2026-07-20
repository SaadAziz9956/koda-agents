package dev.koda.cli

import dev.koda.core.KodaConfig
import dev.koda.core.KodaCore
import dev.koda.core.PermissionMode
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ApprovalRequest
import dev.koda.protocol.ApprovalResponse
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Event
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.TextDelta
import dev.koda.protocol.TokenUsage
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStopReason
import dev.koda.protocol.UserTurn
import dev.koda.core.ApiShape
import dev.koda.core.ProviderConfig
import dev.koda.protocol.CompactSession
import dev.koda.protocol.Interrupt
import dev.koda.protocol.SessionCompacted
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sun.misc.Signal

private const val DIM = "\u001B[2m"
private const val BOLD = "\u001B[1m"
private const val CYAN = "\u001B[36m"
private const val YELLOW = "\u001B[33m"
private const val RED = "\u001B[31m"
private const val RESET = "\u001B[0m"

fun main(args: Array<String>) {
    val cli = CliArgs.parse(args)
    val provider = resolveProvider(cli)

    val config = KodaConfig(
        provider = provider,
        model = cli.model ?: defaultModel(provider),
        cwd = Path.of(System.getProperty("user.dir")),
        permissionMode = cli.permissionMode,
    )

    KodaCore.create(config).use { core ->
        runBlocking {
            val coreJob = core.start()
            val inbox = Channel<Event>(Channel.UNLIMITED)
            val collector = launch { core.events.collect { inbox.send(it) } }

            val sessionId = cli.session ?: UUID.randomUUID().toString().take(8)

            // First Ctrl-C interrupts a running turn; when idle it exits.
            val turnActive = AtomicBoolean(false)
            Signal.handle(Signal("INT")) {
                if (turnActive.get()) {
                    runBlocking {
                        core.submit(Interrupt(UUID.randomUUID().toString(), sessionId))
                    }
                } else {
                    kotlin.system.exitProcess(130)
                }
            }

            if (cli.prompt != null) {
                turnActive.set(true)
                runTurn(core, inbox, sessionId, cli.prompt, headless = true)
                turnActive.set(false)
            } else {
                println("${BOLD}koda${RESET} ${DIM}v0.2.0 — ${config.model} @ ${provider.name} — session $sessionId${RESET}")
                println("${DIM}Type a message; /compact to compress history; Ctrl-C interrupts a turn; 'exit' quits.${RESET}")
                while (true) {
                    print("\n${BOLD}${CYAN}❯${RESET} ")
                    System.out.flush()
                    val line = readlnOrNull()?.trim() ?: break
                    if (line.isEmpty()) continue
                    if (line == "exit" || line == "/quit" || line == "/exit") break
                    if (line == "/compact") {
                        runCompact(core, inbox, sessionId)
                        continue
                    }
                    turnActive.set(true)
                    runTurn(core, inbox, sessionId, line, headless = false)
                    turnActive.set(false)
                }
            }

            collector.cancel()
            coreJob.cancel()
        }
    }
}

private suspend fun runCompact(core: KodaCore, inbox: Channel<Event>, sessionId: String) {
    core.submit(CompactSession(UUID.randomUUID().toString(), sessionId))
    while (true) {
        when (val event = inbox.receive()) {
            is SessionCompacted -> {
                println("${DIM}compacted: ${event.tokensBefore} -> ${event.tokensAfter} tokens${RESET}")
                return
            }
            is ErrorEvent -> {
                println("${RED}error: ${event.message}${RESET}")
                return
            }
            else -> {} // drain unrelated events
        }
    }
}

private suspend fun runTurn(
    core: KodaCore,
    inbox: Channel<Event>,
    sessionId: String,
    text: String,
    headless: Boolean,
) {
    core.submit(UserTurn(UUID.randomUUID().toString(), sessionId, text))
    var streamedAnything = false

    while (true) {
        when (val event = inbox.receive()) {
            is SessionStarted -> {}

            is TextDelta -> {
                if (event.sessionId == sessionId) {
                    print(event.text)
                    System.out.flush()
                    streamedAnything = true
                }
            }

            is AssistantMessage -> {} // already streamed via deltas

            is ToolBegin ->
                println("\n${DIM}⚙ ${event.toolName} ${event.argsJson.take(160)}${RESET}")

            is ToolEnd -> {
                val status = if (event.isError) "${RED}✗${RESET}" else "${DIM}✓${RESET}"
                val preview = event.output.lineSequence().firstOrNull()?.take(120) ?: ""
                println("${DIM}$status ${event.toolName}: $preview${RESET}")
            }

            is ApprovalRequest -> {
                val decision = if (headless && System.console() == null) {
                    println("${YELLOW}Approval needed for ${event.toolName} in headless mode — denying.${RESET}")
                    ApprovalDecision.DENY
                } else {
                    promptApproval(event)
                }
                core.submit(
                    ApprovalResponse(UUID.randomUUID().toString(), sessionId, event.approvalId, decision)
                )
            }

            is TokenUsage -> {}

            is SessionCompacted ->
                println("${DIM}(auto-compacted: ${event.tokensBefore} -> ${event.tokensAfter} tokens)${RESET}")

            is ErrorEvent ->
                println("\n${RED}error: ${event.message}${RESET}")

            is TurnCompleted -> {
                if (streamedAnything) println()
                when (event.stopReason) {
                    TurnStopReason.MAX_ITERATIONS ->
                        println("${YELLOW}(stopped: hit max iterations for this turn)${RESET}")
                    TurnStopReason.INTERRUPTED ->
                        println("${YELLOW}(interrupted)${RESET}")
                    else -> {}
                }
                return
            }

            else -> {}
        }
    }
}

private suspend fun promptApproval(request: ApprovalRequest): ApprovalDecision =
    withContext(Dispatchers.IO) {
        println("\n${YELLOW}${BOLD}approval${RESET}${YELLOW} ${request.summary}${RESET}")
        print("${YELLOW}  [y] allow  [a] always allow  [n] deny > ${RESET}")
        System.out.flush()
        when (readlnOrNull()?.trim()?.lowercase()) {
            "y", "yes" -> ApprovalDecision.APPROVE
            "a", "always" -> ApprovalDecision.APPROVE_ALWAYS
            else -> ApprovalDecision.DENY
        }
    }

private data class CliArgs(
    val prompt: String?,
    val model: String?,
    val providerName: String?,
    val baseUrl: String?,
    val session: String?,
    val permissionMode: PermissionMode,
) {
    companion object {
        fun parse(args: Array<String>): CliArgs {
            var prompt: String? = null
            var model: String? = null
            var provider: String? = null
            var baseUrl: String? = null
            var session: String? = null
            var mode = PermissionMode.DEFAULT

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "-p", "--prompt" -> prompt = args.getOrNull(++i)
                    "--model" -> model = args.getOrNull(++i)
                    "--provider" -> provider = args.getOrNull(++i)
                    "--base-url" -> baseUrl = args.getOrNull(++i)
                    "--session" -> session = args.getOrNull(++i)
                    "--accept-edits" -> mode = PermissionMode.ACCEPT_EDITS
                    "--yolo" -> mode = PermissionMode.YOLO
                    "--help", "-h" -> {
                        println(
                            """
                            koda — autonomous agent harness

                            usage: koda [options]
                              -p, --prompt <text>   run one turn headless and exit
                              --model <id>          model id (default per provider)
                              --provider <name>     anthropic | openai | custom (default: auto from env)
                              --base-url <url>      OpenAI-compatible endpoint for --provider custom
                              --session <id>        resume a session by id
                              --accept-edits        auto-approve file edits (shell still asks)
                              --yolo                no approval prompts at all
                            """.trimIndent()
                        )
                        kotlin.system.exitProcess(0)
                    }
                    else -> if (!arg.startsWith("-") && prompt == null) prompt = arg
                }
                i++
            }
            return CliArgs(prompt, model, provider, baseUrl, session, mode)
        }
    }
}

private fun resolveProvider(cli: CliArgs): ProviderConfig {
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    val openaiKey = System.getenv("OPENAI_API_KEY")
    val customKey = System.getenv("KODA_API_KEY")

    return when (cli.providerName ?: autoDetect(anthropicKey, openaiKey)) {
        "anthropic" -> ProviderConfig(
            name = "anthropic",
            baseUrl = "https://api.anthropic.com",
            apiKey = anthropicKey ?: fail("ANTHROPIC_API_KEY is not set"),
            apiShape = ApiShape.ANTHROPIC_MESSAGES,
        )
        "openai" -> ProviderConfig(
            name = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiKey = openaiKey ?: fail("OPENAI_API_KEY is not set"),
            apiShape = ApiShape.OPENAI_CHAT_COMPLETIONS,
        )
        "custom" -> ProviderConfig(
            name = "custom",
            baseUrl = cli.baseUrl ?: System.getenv("KODA_BASE_URL")
                ?: fail("--base-url or KODA_BASE_URL required for --provider custom"),
            apiKey = customKey ?: openaiKey ?: "",
            apiShape = ApiShape.OPENAI_CHAT_COMPLETIONS,
        )
        else -> fail("Unknown provider: ${cli.providerName}")
    }
}

private fun autoDetect(anthropicKey: String?, openaiKey: String?): String = when {
    anthropicKey != null -> "anthropic"
    openaiKey != null -> "openai"
    else -> fail("No API key found. Set ANTHROPIC_API_KEY or OPENAI_API_KEY, or use --provider custom.")
}

private fun defaultModel(provider: ProviderConfig): String = when (provider.name) {
    "anthropic" -> "claude-sonnet-4-6"
    "openai" -> "gpt-5.1"
    else -> fail("--model is required for --provider custom")
}

private fun fail(message: String): Nothing {
    System.err.println("${RED}koda: $message${RESET}")
    kotlin.system.exitProcess(1)
}
