package dev.koda.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.koda.desktop.theme.KodaTheme
import dev.koda.desktop.ui.AppShell
import dev.koda.desktop.ui.AppView
import dev.koda.desktop.ui.CommandPalette
import dev.koda.protocol.ApprovalDecision

/**
 * Koda Desktop — a Compose Multiplatform surface and thin protocol client. It
 * connects to a Koda daemon over WebSocket (KODA_DAEMON, default
 * ws://127.0.0.1:4477) and drives the same agent as the CLI/TUI.
 */
fun main(): Unit = application {
    val scope = rememberCoroutineScope()
    val model = remember { AppModel(DaemonClient(scope), scope) }
    var palette by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(AppView.Connect) }
    var dark by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.connect(model.daemonUrl) }
    // Land on Home once the first connection succeeds.
    LaunchedEffect(model.everConnected) { if (model.everConnected && view == AppView.Connect) view = AppView.Home }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Koda",
        onKeyEvent = { e ->
            val cmd = e.isMetaPressed || e.isCtrlPressed
            when {
                e.type != KeyEventType.KeyDown -> false
                cmd && e.key == Key.K -> { palette = true; true }
                palette && e.key == Key.Escape -> { palette = false; true }
                model.pendingApproval != null && e.key == Key.Y -> { model.approve(ApprovalDecision.APPROVE); true }
                model.pendingApproval != null && e.key == Key.A -> { model.approve(ApprovalDecision.APPROVE_ALWAYS); true }
                model.pendingApproval != null && e.key == Key.N -> { model.approve(ApprovalDecision.DENY); true }
                model.pendingClarify != null -> {
                    val n = when (e.key) {
                        Key.One -> 1; Key.Two -> 2; Key.Three -> 3; Key.Four -> 4; Key.Five -> 5; Key.Six -> 6; else -> 0
                    }
                    val opt = if (n > 0) model.pendingClarify?.options?.getOrNull(n - 1) else null
                    if (opt != null) { model.answerClarify(opt.label); true } else false
                }
                else -> false
            }
        },
    ) {
        KodaTheme(dark = dark) {
            AppRoot(
                model = model,
                view = view, setView = { view = it },
                palette = palette, setPalette = { palette = it },
                dark = dark, onToggleTheme = { dark = !dark },
            )
        }
    }
}

@Composable
private fun AppRoot(
    model: AppModel,
    view: AppView, setView: (AppView) -> Unit,
    palette: Boolean, setPalette: (Boolean) -> Unit,
    dark: Boolean, onToggleTheme: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AppShell(model, view = view, setView = setView, onOpenPalette = { setPalette(true) }, dark = dark, onToggleTheme = onToggleTheme)
        if (palette) CommandPalette(model, onClose = { setPalette(false) }, onNavigate = { setView(it); setPalette(false) })
    }
}
