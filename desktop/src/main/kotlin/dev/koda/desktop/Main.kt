package dev.koda.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.koda.desktop.theme.KodaTheme
import dev.koda.desktop.ui.AppShell

/**
 * Koda Desktop — a Compose Multiplatform surface. A thin protocol client: it
 * connects to a Koda daemon over WebSocket (KODA_DAEMON, default
 * ws://127.0.0.1:4477) and renders the same agent the CLI/TUI drive.
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Koda") {
        KodaTheme {
            val scope = rememberCoroutineScope()
            val model = remember {
                val client = DaemonClient(scope)
                val m = AppModel(client, scope)
                client.connect(System.getenv("KODA_DAEMON")?.takeIf { it.isNotBlank() } ?: "ws://127.0.0.1:4477")
                m
            }
            AppShell(model)
        }
    }
}
