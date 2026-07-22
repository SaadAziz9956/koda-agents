package dev.koda.cli

import dev.koda.client.StartupException
import dev.koda.client.resolveStartup
import dev.koda.core.*
import dev.koda.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sun.misc.Signal
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

internal const val DIM = "\u001B[2m"
internal const val BOLD = "\u001B[1m"
internal const val CYAN = "\u001B[36m"
internal const val YELLOW = "\u001B[33m"
internal const val RED = "\u001B[31m"
internal const val RESET = "\u001B[0m"

fun main(args: Array<String>) {
    val startup = try {
        resolveStartup(args)
    } catch (e: StartupException) {
        System.err.println("${RED}koda: ${e.message}${RESET}")
        kotlin.system.exitProcess(1)
    }
    val config = startup.config
    val provider = config.provider

    KodaCore.create(config).use { core ->
        runBlocking {
            val coreJob = core.start()
            val inbox = Channel<Event>(Channel.UNLIMITED)
            val collector = launch { core.events.collect { inbox.send(it) } }

            val state = CliState(
                sessionId = startup.sessionId,
                mode = config.permissionMode,
            )
            val commandContext = CommandContext(core, inbox, state)

            // First Ctrl-C interrupts a running turn; when idle it exits.
            val turnActive = AtomicBoolean(false)
            Signal.handle(Signal("INT")) {
                if (turnActive.get()) {
                    runBlocking {
                        core.submit(Interrupt(UUID.randomUUID().toString(), state.sessionId))
                    }
                } else {
                    kotlin.system.exitProcess(130)
                }
            }

            val headlessPrompt = startup.prompt
            if (headlessPrompt != null) {
                turnActive.set(true)
                runTurn(core, inbox, state, headlessPrompt, headless = true)
                turnActive.set(false)
            } else {
                println("${BOLD}koda${RESET} ${DIM}v0.6.0 — ${config.model} @ ${provider.name} — session ${state.sessionId}${RESET}")
                println("${DIM}Type a message; /help for commands; Ctrl-C interrupts a turn; 'exit' quits.${RESET}")
                while (true) {
                    val pct = state.contextPercent?.let { "${DIM}[$it%]${RESET} " } ?: ""
                    print("\n$pct${BOLD}${CYAN}❯${RESET} ")
                    System.out.flush()
                    val line = readlnOrNull()?.trim() ?: break
                    if (line.isEmpty()) continue
                    if (line == "exit" || line == "/quit" || line == "/exit") break
                    val outcome = dispatchCommand(line, commandContext)
                    val turnText = if (outcome != null) outcome.turnText ?: continue else line
                    turnActive.set(true)
                    runTurn(core, inbox, state, turnText, headless = false)
                    turnActive.set(false)
                }
            }

            collector.cancel()
            coreJob.cancel()
        }
    }
}

private suspend fun runTurn(
    core: KodaCore,
    inbox: Channel<Event>,
    state: CliState,
    text: String,
    headless: Boolean,
) {
    val sessionId = state.sessionId
    core.submit(UserTurn(UUID.randomUUID().toString(), sessionId, text))
    var streamedAnything = false

    while (true) {
        when (val event = inbox.receive()) {
            is SessionStarted ->
                if (event.sessionId == sessionId) state.contextLength = event.contextLength

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

            is TokenUsage ->
                if (event.sessionId == sessionId) state.lastPromptTokens = event.inputTokens

            is SessionCompacted ->
                println("${DIM}(auto-compacted: ${event.messagesBefore} -> ${event.messagesAfter} messages)${RESET}")

            is Notice ->
                println("${DIM}${event.text}${RESET}")

            is ErrorEvent ->
                println("\n${RED}error: ${event.message}${RESET}")

            is TurnCompleted -> {
                if (streamedAnything) println()
                state.turnCount++
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
