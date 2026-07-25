package dev.koda.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.KodaColors
import dev.koda.desktop.theme.LocalKoda
import dev.koda.protocol.PermissionModeSetting

private val SETTINGS_TABS = listOf(
    "connection" to "Connection",
    "permissions" to "Permissions",
    "model" to "Model",
    "surfaces" to "Surfaces",
    "appearance" to "Appearance",
    "scheduled" to "Scheduled tasks",
)

private val TAB_META = mapOf(
    "connection" to ("Connection" to "How the app reaches your daemon."),
    "permissions" to ("Permissions" to "What the agent may do without asking."),
    "model" to ("Model" to "Default model and provider routing."),
    "surfaces" to ("Surfaces" to "Where the same agent stays live."),
    "appearance" to ("Appearance" to "Theme, motion, and density."),
    "scheduled" to ("Scheduled tasks" to "Recurring agent runs."),
)

@Composable
fun SettingsScreen(model: AppModel, dark: Boolean, onToggleTheme: () -> Unit, onClose: () -> Unit, initialTab: String = "connection") {
    val k = LocalKoda.current
    var tab by remember { mutableStateOf(initialTab) }
    Row(Modifier.fillMaxSize().background(k.bg)) {
        // ── Nav ──
        Column(
            Modifier.width(220.dp).fillMaxHeight().background(k.rail)
                .padding(start = 12.dp, end = 13.dp, top = 18.dp, bottom = 18.dp),
        ) {
            Text("Settings", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = k.text, modifier = Modifier.padding(start = 8.dp, bottom = 14.dp))
            SETTINGS_TABS.forEach { (id, label) -> NavRow(label, tab == id) { tab = id } }
        }
        VDivider()
        // ── Content ──
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 34.dp)) {
            Column(Modifier.widthIn(max = 620.dp)) {
                val (title, sub) = TAB_META[tab]!!
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.19).sp, color = k.text)
                Text(sub, fontSize = 13.sp, color = k.dim, modifier = Modifier.padding(top = 4.dp, bottom = 26.dp))
                when (tab) {
                    "connection" -> ConnectionTab(model)
                    "permissions" -> PermissionsTab(model)
                    "model" -> ModelTab(model)
                    "appearance" -> AppearanceTab(dark, onToggleTheme)
                    "scheduled" -> ScheduledTab(model)
                    else -> GenericTab(title)
                }
            }
        }
    }
}

@Composable
private fun NavRow(label: String, active: Boolean, onClick: () -> Unit) {
    val k = LocalKoda.current
    Box(
        Modifier.fillMaxWidth().padding(bottom = 1.dp).clip(RoundedCornerShape(8.dp))
            .background(if (active) k.surface else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) k.text else k.dim)
    }
}

// ── Tabs ──

@Composable
private fun ConnectionTab(model: AppModel) {
    val k = LocalKoda.current
    var url by remember { mutableStateOf(model.daemonUrl) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Daemon address", fontSize = 12.sp, color = k.dim, fontWeight = FontWeight.Medium)
            Box(Modifier.padding(top = 6.dp)) {
                EmberField(url, { url = it }, "ws://127.0.0.1:4477", Modifier.fillMaxWidth(), mono = true, onSubmit = { model.connect(url) })
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (model.status.name == "Connected") k.ok else k.faint))
            Text(model.status.name, fontSize = 13.sp, color = k.text, modifier = Modifier.weight(1f))
            Text(model.daemonUrl, fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.dim)
        }

        // ── Anthropic account (bring-your-own key) ──
        HDivider()
        AnthropicAccount(model)

        ToggleRow("Auto-reconnect", "Silently retry if the daemon drops", true)
        ToggleRow("Trust local network daemons", "Skip confirmation for 127.0.0.1", false)
    }
}

@Composable
private fun AnthropicAccount(model: AppModel) {
    val k = LocalKoda.current
    var key by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Anthropic account", fontSize = 12.sp, color = k.dim, fontWeight = FontWeight.Medium)
        if (model.authConfigured) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(k.ok))
                Text("Connected", fontSize = 13.sp, color = k.text)
                Text(model.authLabel, fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.dim, modifier = Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(7.dp)).border(1.dp, k.border, RoundedCornerShape(7.dp)).clickable { model.signOut() }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text("Sign out", fontSize = 12.sp, color = k.dim)
                }
            }
        } else {
            Text("Paste an Anthropic API key. Koda validates it, then stores it on the daemon (~/.koda, owner-only).", fontSize = 12.sp, color = k.dim, lineHeight = 17.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EmberField(key, { key = it }, "sk-ant-…", Modifier.weight(1f), mono = true, onSubmit = { model.setApiKey(key) })
                EmberButton(if (model.authChecking) "Checking…" else "Connect", onClick = { model.setApiKey(key) }, enabled = key.isNotBlank() && !model.authChecking)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable { openUrl("https://console.anthropic.com/settings/keys") }.padding(vertical = 2.dp)) {
                    Text("Get a key ↗", fontSize = 12.sp, color = k.accent)
                }
                if (model.authError != null) Text("· ${model.authError}", fontSize = 12.sp, color = k.danger)
            }
        }
    }
}

/** Open a URL in the user's default browser (desktop only). */
internal fun openUrl(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

@Composable
private fun PermissionsTab(model: AppModel) {
    val k = LocalKoda.current
    val rows = listOf(
        Triple("read_file", "Read any file in the workspace", true),
        Triple("edit_file", "Modify files (shows a diff first)", false),
        Triple("run_shell", "Execute shell commands", false),
        Triple("git", "Commit, branch, and push", false),
    )
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Default mode", fontSize = 13.sp, color = k.text, modifier = Modifier.weight(1f))
            Segmented(model.mode) { model.changeMode(it) }
        }
        rows.forEach { (tool, desc, ok) ->
            Row(
                Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent).padding(vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(tool, fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.accentSoft, modifier = Modifier.width(120.dp))
                Text(desc, fontSize = 13.sp, color = k.dim, modifier = Modifier.weight(1f))
                ModePill(if (ok) "Always" else "Ask", ok)
            }
            HDivider()
        }
    }
}

@Composable
private fun ModelTab(model: AppModel) {
    val k = LocalKoda.current
    var newModel by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Active", fontSize = 13.sp, color = k.text, modifier = Modifier.weight(1f))
            Text(model.activeModel.ifBlank { model.modelName.ifBlank { "—" } }, fontFamily = JetBrainsMono, fontSize = 12.5f.sp, color = k.accentSoft)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Provider", fontSize = 13.sp, color = k.text, modifier = Modifier.weight(1f))
            Text(model.providerName.ifBlank { "—" }, fontFamily = JetBrainsMono, fontSize = 12.5f.sp, color = k.dim)
        }
        HDivider()
        if (model.availableModels.isNotEmpty()) {
            Text("Available", fontSize = 12.sp, color = k.dim, fontWeight = FontWeight.Medium)
            model.availableModels.forEach { id ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { model.setModelId(id) }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(id, fontFamily = JetBrainsMono, fontSize = 12.5f.sp, color = k.text, modifier = Modifier.weight(1f))
                    if (id == model.activeModel) Text("✓", fontSize = 12.sp, color = k.accent)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberField(newModel, { newModel = it }, "model id", Modifier.weight(1f), mono = true)
                Spacer(Modifier.width(8.dp))
                EmberButton("Set", onClick = { if (newModel.isNotBlank()) { model.setModelId(newModel); newModel = "" } })
            }
        }
    }
}

@Composable
private fun AppearanceTab(dark: Boolean, onToggleTheme: () -> Unit) {
    val k = LocalKoda.current
    Column {
        Text("Theme", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = k.dim, modifier = Modifier.padding(bottom = 10.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeCard(Modifier.weight(1f), "Ember Dark", selected = dark, bg = Color(0xFF1C1B1A), rail = Color(0xFF181716), line = Color(0xFF443F3C), accent = Color(0xFFF0663C), border = Color(0xFF322F2D)) { if (!dark) onToggleTheme() }
            ThemeCard(Modifier.weight(1f), "Ember Light", selected = !dark, bg = Color(0xFFFBFAF9), rail = Color(0xFFEDEAE6), line = Color(0xFFC7BFB9), accent = Color(0xFFD24A22), border = Color(0xFFDAD4D0)) { if (dark) onToggleTheme() }
        }
        ToggleRow("Reduce motion", "Honor system preference; disable shimmer & springs", false)
        ToggleRow("Compact density", "Tighter row heights across lists", true)
        ToggleRow("Show telemetry", "Token counts and elapsed time inline", true)
    }
}

@Composable
private fun ScheduledTab(model: AppModel) {
    val k = LocalKoda.current
    var schedPrompt by remember { mutableStateOf("") }
    var schedEvery by remember { mutableStateOf("3600") }
    Column {
        model.schedules.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.prompt, fontSize = 12.5f.sp, color = k.text, maxLines = 1)
                    Text("every ${s.everySeconds}s", fontSize = 11.sp, fontFamily = JetBrainsMono, color = k.dim)
                }
                EmberTextButton("Cancel", onClick = { model.cancelSchedule(s.id) }, danger = true)
            }
            HDivider()
        }
        if (model.schedules.isEmpty()) Text("No scheduled tasks.", fontSize = 12.sp, fontFamily = JetBrainsMono, color = k.faint, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EmberField(schedPrompt, { schedPrompt = it }, "prompt to run…", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            EmberField(schedEvery, { schedEvery = it }, "secs", Modifier.width(72.dp), mono = true)
            Spacer(Modifier.width(8.dp))
            EmberButton("Add", onClick = {
                val secs = schedEvery.toLongOrNull() ?: 3600
                if (schedPrompt.isNotBlank()) { model.createSchedule(schedPrompt, secs); schedPrompt = "" }
            })
        }
    }
}

@Composable
private fun GenericTab(title: String) {
    val k = LocalKoda.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, k.border, RoundedCornerShape(12.dp)).padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("$title — configured here.", fontFamily = JetBrainsMono, fontSize = 13.sp, color = k.faint)
    }
}

// ── Shared bits ──

@Composable
private fun ThemeCard(modifier: Modifier, label: String, selected: Boolean, bg: Color, rail: Color, line: Color, accent: Color, border: Color, onClick: () -> Unit) {
    val k = LocalKoda.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.clip(shape).border(2.dp, if (selected) k.accent else k.border, shape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(1.dp, border, RoundedCornerShape(8.dp)).padding(9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(22.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(rail))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(8.dp).clip(RoundedCornerShape(3.dp)).background(line))
                Box(Modifier.fillMaxWidth(0.4f).height(8.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            }
        }
        Text(label, fontSize = 12.5f.sp, color = k.text)
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, initial: Boolean) {
    val k = LocalKoda.current
    var on by remember { mutableStateOf(initial) }
    HDivider()
    Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.5f.sp, color = k.text)
            Text(desc, fontSize = 12.sp, color = k.dim, modifier = Modifier.padding(top = 2.dp))
        }
        TogglePill(on) { on = !on }
    }
}

@Composable
private fun TogglePill(on: Boolean, onToggle: () -> Unit) {
    val k = LocalKoda.current
    val offset by animateDpAsState(if (on) 16.dp else 0.dp)
    Box(
        Modifier.width(38.dp).height(22.dp).clip(RoundedCornerShape(12.dp)).background(if (on) k.accent else k.strong).clickable(onClick = onToggle).padding(2.dp),
    ) {
        Box(Modifier.padding(start = offset).size(18.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

@Composable
private fun ModePill(text: String, ok: Boolean) {
    val k = LocalKoda.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.clip(shape).background(if (ok) k.sel else k.raised).border(1.dp, if (ok) k.accent else k.border, shape).padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = if (ok) k.accent else k.dim)
    }
}

@Composable
private fun Segmented(mode: PermissionModeSetting, onPick: (PermissionModeSetting) -> Unit) {
    val k = LocalKoda.current
    val opts = listOf(
        PermissionModeSetting.DEFAULT to "default",
        PermissionModeSetting.ACCEPT_EDITS to "accept-edits",
        PermissionModeSetting.YOLO to "yolo",
    )
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(k.bg).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        opts.forEach { (m, label) ->
            val on = m == mode
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).background(if (on) k.accent else Color.Transparent).clickable { onPick(m) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, fontFamily = JetBrainsMono, fontSize = 12.sp, color = if (on) k.onAccent else k.dim)
            }
        }
    }
}
