package dev.koda.daemon

import dev.koda.core.KodaConfig
import dev.koda.core.KodaCore
import dev.koda.core.PermissionMode
import dev.koda.protocol.Event
import dev.koda.protocol.ProtocolJson
import dev.koda.protocol.Submission
import dev.koda.providers.ApiShape
import dev.koda.providers.ProviderConfig
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * The Koda daemon: the same core the CLI embeds, served over WebSocket so
 * any surface (TUI, gateway, desktop, SDK) can attach. Protocol: client
 * sends Submission JSON frames, server pushes every Event as a JSON frame.
 *
 * v0 is deliberately minimal — one shared core, localhost only, no auth.
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(args.indexOf("--port") + 1)?.toIntOrNull()?.takeIf { "--port" in args } ?: 4477

    val apiKey = System.getenv("ANTHROPIC_API_KEY")
        ?: error("ANTHROPIC_API_KEY is not set")

    val config = KodaConfig(
        provider = ProviderConfig(
            name = "anthropic",
            baseUrl = "https://api.anthropic.com",
            apiKey = apiKey,
            apiShape = ApiShape.ANTHROPIC_MESSAGES,
        ),
        model = System.getenv("KODA_MODEL") ?: "claude-sonnet-4-6",
        cwd = Path.of(System.getProperty("user.dir")),
        permissionMode = PermissionMode.DEFAULT,
    )

    val core = KodaCore.create(config)
    core.start()

    println("koda daemon listening on ws://127.0.0.1:$port/ws")

    embeddedServer(CIO, port = port, host = "127.0.0.1") {
        install(WebSockets)
        routing {
            webSocket("/ws") {
                val pusher = launch {
                    core.events.collect { event: Event ->
                        send(Frame.Text(ProtocolJson.encodeToString(Event.serializer(), event)))
                    }
                }
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val submission =
                                ProtocolJson.decodeFromString(Submission.serializer(), frame.readText())
                            core.submit(submission)
                        }
                    }
                } finally {
                    pusher.cancel()
                }
            }
        }
    }.start(wait = true)
}
