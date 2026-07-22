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
import dev.koda.desktop.ui.CommandPalette
import dev.koda.desktop.ui.ConnectScreen
import dev.koda.desktop.ui.SettingsScreen
import dev.koda.protocol.ApprovalDecision

/**
 * Koda Desktop — a Compose Multiplatform surface and thin protocol client. It
 * connects to a Koda daemon over WebSocket (KODA_DAEMON, default
 * ws://127.0.0.1:4477) and drives the same agent as the CLI/TUI.
 */
fun main() = application {
    val scope = rememberCoroutineScope()
    val model = remember { AppModel(DaemonClient(scope), scope) }
    var palette by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { model.connect(model.daemonUrl) }

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
                else -> false
            }
        },
    ) {
        KodaTheme {
            AppRoot(
                model = model,
                palette = palette, setPalette = { palette = it },
                settings = settings, setSettings = { settings = it },
            )
        }
    }
}

@Composable
private fun AppRoot(
    model: AppModel,
    palette: Boolean, setPalette: (Boolean) -> Unit,
    settings: Boolean, setSettings: (Boolean) -> Unit,
) {
    if (!model.everConnected) {
        ConnectScreen(model)
        return
    }
    Box(Modifier.fillMaxSize()) {
        AppShell(model, onOpenPalette = { setPalette(true) }, onOpenSettings = { setSettings(true) })
        if (palette) CommandPalette(model, onClose = { setPalette(false) }, onOpenSettings = { setSettings(true) })
        if (settings) SettingsScreen(model, onClose = { setSettings(false) })
    }
}
