package dev.koda.client

import dev.koda.core.ApiShape
import dev.koda.core.KodaConfig
import dev.koda.core.PermissionMode
import dev.koda.core.ProviderConfig
import java.nio.file.Path
import java.util.UUID

/**
 * Startup wiring shared by every Koda surface (CLI, TUI, and the gateway to
 * come). Turning `argv` into a ready-to-run [KodaConfig] is identical work
 * across surfaces; only how a failure is *displayed* differs, so this layer
 * throws [StartupException] and lets each surface render and exit its own way.
 */

/** A startup misconfiguration — missing key, unknown provider, missing model. */
class StartupException(message: String) : Exception(message)

/** Parsed command-line arguments common to the surfaces. */
data class CliArgs(
    val prompt: String?,
    val model: String?,
    val providerName: String?,
    val baseUrl: String?,
    val session: String?,
    val permissionMode: PermissionMode,
) {
    companion object {
        /** Parse argv. `--help`/`-h` prints usage and exits the process (0). */
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

/** Everything a surface needs to boot a session. */
data class Startup(
    val config: KodaConfig,
    val sessionId: String,
    val prompt: String?,
)

/**
 * Parse argv, resolve the provider from flags + environment, and assemble the
 * session config. Throws [StartupException] on any misconfiguration.
 */
fun resolveStartup(args: Array<String>): Startup {
    val cli = CliArgs.parse(args)
    val provider = resolveProvider(cli)
    val config = KodaConfig(
        provider = provider,
        model = cli.model ?: defaultModel(provider),
        cwd = Path.of(System.getProperty("user.dir")),
        permissionMode = cli.permissionMode,
    )
    val sessionId = cli.session ?: UUID.randomUUID().toString().take(8)
    return Startup(config, sessionId, cli.prompt)
}

private fun resolveProvider(cli: CliArgs): ProviderConfig {
    val anthropicKey = System.getenv("ANTHROPIC_API_KEY")
    val openaiKey = System.getenv("OPENAI_API_KEY")
    val customKey = System.getenv("KODA_API_KEY")

    return when (cli.providerName ?: autoDetect(anthropicKey, openaiKey)) {
        "anthropic" -> ProviderConfig(
            name = "anthropic",
            baseUrl = "https://api.anthropic.com",
            apiKey = anthropicKey ?: throw StartupException("ANTHROPIC_API_KEY is not set"),
            apiShape = ApiShape.ANTHROPIC_MESSAGES,
        )

        "openai" -> ProviderConfig(
            name = "openai",
            baseUrl = "https://api.openai.com/v1",
            apiKey = openaiKey ?: throw StartupException("OPENAI_API_KEY is not set"),
            apiShape = ApiShape.OPENAI_CHAT_COMPLETIONS,
        )

        "custom" -> ProviderConfig(
            name = "custom",
            baseUrl = cli.baseUrl ?: System.getenv("KODA_BASE_URL")
            ?: throw StartupException("--base-url or KODA_BASE_URL required for --provider custom"),
            apiKey = customKey ?: openaiKey ?: "",
            apiShape = ApiShape.OPENAI_CHAT_COMPLETIONS,
        )

        else -> throw StartupException("Unknown provider: ${cli.providerName}")
    }
}

private fun autoDetect(anthropicKey: String?, openaiKey: String?): String = when {
    anthropicKey != null -> "anthropic"
    openaiKey != null -> "openai"
    else -> throw StartupException("No API key found. Set ANTHROPIC_API_KEY or OPENAI_API_KEY, or use --provider custom.")
}

private fun defaultModel(provider: ProviderConfig): String = when (provider.name) {
    "anthropic" -> "claude-sonnet-4-6"
    "openai" -> "gpt-5.1"
    else -> throw StartupException("--model is required for --provider custom")
}
