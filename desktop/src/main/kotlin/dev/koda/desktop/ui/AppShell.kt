package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.ConnStatus
import dev.koda.desktop.Line
import dev.koda.desktop.ToolStatus
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.koda.desktop.theme.Ember
import dev.koda.desktop.theme.LocalKoda
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ClarifyRequest
import dev.koda.protocol.PermissionModeSetting

/** Top-level route, selected by the persistent top nav. Home/Code share the
 *  rail+center+right shell; Connect and Settings are full-width destinations. */
enum class AppView { Home, Code, Connect, Settings }

@Composable
fun AppShell(
    model: AppModel,
    view: AppView,
    setView: (AppView) -> Unit,
    onOpenPalette: () -> Unit,
    dark: Boolean,
    onToggleTheme: () -> Unit,
) {
    val k = LocalKoda.current
    Column(Modifier.fillMaxSize().background(k.bg)) {
        TopReviewNav(view, setView, onOpenPalette, dark, onToggleTheme)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                AppView.Connect -> ConnectScreen(model)
                AppView.Settings -> SettingsScreen(model, dark = dark, onToggleTheme = onToggleTheme, onClose = { setView(AppView.Home) })
                else -> Row(Modifier.fillMaxSize()) {
                    Rail(model, view, setView, Modifier.width(224.dp).fillMaxHeight())
                    VDivider()
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(model, onOpenPalette, onOpenSettings = { setView(AppView.Settings) })
                        HDivider()
                        if (model.status != ConnStatus.Connected) ReconnectBanner(model)
                        if (model.everConnected && !model.authConfigured) AuthBanner(model)
                        if (view == AppView.Code) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { CodeEditor(model) }
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth()) { Transcript(model) }
                            HDivider()
                            Composer(model, onOpenSettings = { setView(AppView.Settings) })
                        }
                    }
                    // The right panel (Rewind/Files/Git) is present in both Home and Code.
                    VDivider()
                    ContextPanel(model, Modifier.width(344.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun TopReviewNav(
    view: AppView, setView: (AppView) -> Unit,
    onOpenPalette: () -> Unit,
    dark: Boolean, onToggleTheme: () -> Unit,
) {
    val k = LocalKoda.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(k.rail).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(k.accentSoft, k.accent))))
        Spacer(Modifier.width(9.dp))
        Text("Koda", fontSize = 13.5f.sp, fontWeight = FontWeight.SemiBold, color = k.text)
        Spacer(Modifier.width(9.dp))
        Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, k.border, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
            Text("v0.9", fontFamily = Ember.mono, fontSize = 10.sp, color = k.faint)
        }
        Spacer(Modifier.width(14.dp))
        NavTab("Home", view == AppView.Home) { setView(AppView.Home) }
        NavTab("Code", view == AppView.Code) { setView(AppView.Code) }
        NavTab("Connect", view == AppView.Connect) { setView(AppView.Connect) }
        NavTab("Settings", view == AppView.Settings) { setView(AppView.Settings) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.height(26.dp).clip(RoundedCornerShape(7.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(7.dp))
                .clickable(onClick = onOpenPalette).padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Search", fontSize = 11.5f.sp, color = k.dim)
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(k.raised).border(1.dp, k.border, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
                Text("⌘K", fontFamily = Ember.mono, fontSize = 10.sp, color = k.dim)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(7.dp))
                .clickable(onClick = onToggleTheme),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (dark) KIcons.moon else KIcons.sun, contentDescription = "theme", tint = k.dim, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun NavTab(label: String, active: Boolean, onClick: () -> Unit) {
    val k = LocalKoda.current
    Box(
        Modifier.height(28.dp).clip(RoundedCornerShape(8.dp))
            .then(if (active) Modifier.background(k.surface).border(1.dp, k.border, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) k.text else k.dim)
    }
}

@Composable
private fun ContextPanel(model: AppModel, modifier: Modifier = Modifier) {
    val k = LocalKoda.current
    var tab by remember { mutableStateOf(0) }
    Column(modifier.background(k.rail)) {
        // 48px underline tab strip.
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TabButton("Rewind", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabButton("Files", tab == 1, Modifier.weight(1f)) { tab = 1 }
            TabButton("Git", tab == 2, Modifier.weight(1f)) { tab = 2 }
        }
        HDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> RewindPanel(model, Modifier.fillMaxSize())
                1 -> FileExplorer(model)
                else -> GitPanel(model, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TabButton(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val k = LocalKoda.current
    Column(
        modifier.fillMaxHeight().clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(label, fontSize = 12.5f.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) k.text else k.dim)
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) k.accent else Color.Transparent))
    }
}

@Composable
private fun ReconnectBanner(model: AppModel) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            if (model.status == ConnStatus.Connecting) "reconnecting to ${model.daemonUrl}…" else "disconnected — retrying ${model.daemonUrl}",
            fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Shown across Home/Code when the daemon has no Anthropic key yet — a compact
 *  card to connect right here (paste key → validate) without opening Settings. */
@Composable
private fun AuthBanner(model: AppModel) {
    val k = LocalKoda.current
    var key by remember { mutableStateOf("") }
    val shape = RoundedCornerShape(13.dp)
    Box(Modifier.fillMaxWidth().background(k.rail).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(shape).background(k.surface)
                .border(1.dp, k.strong, shape).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Gradient logo tile with a key glyph, matching the Connect card.
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(k.accentSoft, k.accent))),
                    contentAlignment = Alignment.Center,
                ) { Text("⚿", fontSize = 17.sp, color = k.onAccent) }
                Column(Modifier.weight(1f)) {
                    Text("Connect your Anthropic account", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = k.text)
                    Text("Paste an API key to start — Koda validates it and stores it locally.", fontSize = 12.5f.sp, color = k.dim, modifier = Modifier.padding(top = 1.dp))
                }
                Box(Modifier.clip(RoundedCornerShape(7.dp)).clickable { openUrl("https://console.anthropic.com/settings/keys") }.padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("Get a key ↗", fontSize = 12.5f.sp, fontWeight = FontWeight.Medium, color = k.accent)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(k.bg).border(1.dp, k.border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 2.dp)) {
                    EmberField(key, { key = it }, "sk-ant-…", Modifier.fillMaxWidth(), mono = true, bordered = false, onSubmit = { model.setApiKey(key) })
                }
                EmberButton(if (model.authChecking) "Checking…" else "Connect", onClick = { model.setApiKey(key) }, enabled = key.isNotBlank() && !model.authChecking)
            }
            model.authError?.let { Text(it, fontSize = 12.sp, color = k.danger) }
        }
    }
}

@Composable
private fun TopBar(model: AppModel, onOpenPalette: () -> Unit, onOpenSettings: () -> Unit) {
    val k = LocalKoda.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(k.bg).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("koda/${model.sessionId}", fontFamily = Ember.mono, fontSize = 12.sp, color = k.text)
        Spacer(Modifier.width(8.dp)); Text("·", color = k.faint); Spacer(Modifier.width(8.dp))
        Text(model.activeModel.ifBlank { model.modelName }.ifBlank { "—" }, fontSize = 12.sp, color = k.dim)
        if (model.providerName.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, k.border, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(model.providerName, fontFamily = Ember.mono, fontSize = 10.sp, color = k.faint)
            }
        }
        Spacer(Modifier.weight(1f))
        ContextRing(model.contextPercent)
        Spacer(Modifier.width(7.dp))
        Text(model.contextPercent?.let { "$it%" } ?: "—", fontSize = 12.sp, fontFamily = Ember.mono, color = k.dim)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(KIcons.gear, contentDescription = "settings", tint = k.dim, modifier = Modifier.size(15.dp)) }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(ChipShape).border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Transcript(model: AppModel) {
    val state = rememberLazyListState()
    val total = model.lines.size +
        (if (model.reasoning.isNotBlank()) 1 else 0) +
        (if (model.streaming.isNotBlank()) 1 else 0) +
        (if (model.pendingApproval != null) 1 else 0)
    // Follow new content only when the user is already at the bottom, so
    // scrolling back to read isn't yanked away mid-stream.
    val atBottom by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val count = state.layoutInfo.totalItemsCount
            count == 0 || last >= count - 1
        }
    }
    LaunchedEffect(total, model.streaming, model.reasoning, model.activity, model.pendingClarify) {
        if (total > 0 && atBottom) state.scrollToItem(total - 1)
    }
    // Conversation column: centered, max 760, 28px gutters. Agent sub-items
    // (tools, activity, reply, clarify, approval) indent 36px to sit under the
    // agent avatar, matching the handoff.
    val convo = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 28.dp)
    val convoAgent = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 64.dp, end = 28.dp)
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 14.dp),
    ) {
        // Split at the last user message so the current turn's agent block can be
        // headed by the agent avatar + activity pill, with its tools/reply indented
        // beneath — matching the handoff's mid-stream layout.
        val lastUser = model.lines.indexOfLast { it is Line.User }
        val head = if (lastUser >= 0) model.lines.take(lastUser + 1) else model.lines.toList()
        val tail = if (lastUser >= 0) model.lines.drop(lastUser + 1) else emptyList()
        items(head, key = { it.key }) { line ->
            when (line) {
                is Line.User -> Box(convo) { UserLine(line) }
                is Line.Assistant -> Box(convo) { AssistantLine(line) }
                else -> Box(convoAgent) { LineView(line) }
            }
        }
        // Agent-turn header: avatar + activity pill (live "Responding…" or the
        // frozen "Done" summary once the turn finishes).
        if (model.working || tail.isNotEmpty()) {
            item(key = -4) {
                Row(convo, verticalAlignment = Alignment.Top) {
                    AgentAvatar()
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { ActivityLine(model, model.activity ?: "Working…") }
                }
            }
        }
        items(tail, key = { it.key }) { line ->
            Box(convoAgent) {
                // The current turn's reply renders as plain markdown under the
                // header avatar (no second avatar); tools/notes via LineView.
                if (line is Line.Assistant) MarkdownText(line.text) else LineView(line)
            }
        }
        if (model.reasoning.isNotBlank()) item(key = -3) { Box(convoAgent) { ReasoningBlock(model.reasoning) } }
        if (model.streaming.isNotBlank()) item(key = -1) { Box(convoAgent) { StreamingLine(model.streaming) } }
        model.pendingApproval?.let { req -> item(key = -2) { Box(convoAgent) { ApprovalCard(req.summary) { model.approve(it) } } } }
        model.pendingClarify?.let { c -> item(key = -5) { Box(convoAgent) { ClarifyCard(c, model) } } }
    }
}

@Composable
private fun UserAvatar() {
    val k = LocalKoda.current
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(k.raised).border(1.dp, k.border, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) { Text("JD", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = k.dim) }
}

@Composable
private fun AgentAvatar() {
    val k = LocalKoda.current
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Brush.linearGradient(listOf(k.accentSoft, k.accent))),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(3.dp)).background(k.onAccent.copy(alpha = 0.85f))) }
}

@Composable
private fun UserLine(line: Line.User) {
    val k = LocalKoda.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UserAvatar()
        Text(line.text, fontSize = 14.5f.sp, lineHeight = 22.sp, color = k.text, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AssistantLine(line: Line.Assistant) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentAvatar()
        Box(Modifier.weight(1f).enterFade()) { MarkdownText(line.text) }
    }
}

@Composable
private fun LineView(line: Line) {
    when (line) {
        is Line.Tool -> ToolCard(line)
        is Line.Note -> Text(
            line.text,
            color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = Ember.mono, fontSize = 12.sp,
        )
        else -> {}
    }
}

@Composable
private fun ReasoningBlock(text: String) {
    Column {
        Text("THINKING", fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
        Text(text, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
    }
}

@Composable
private fun StreamingLine(text: String) {
    val alpha = blinkAlpha()
    val accent = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = accent.copy(alpha = alpha))) { append("▋") }
        },
        color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp,
    )
}

@Composable
private fun ActivityLine(model: AppModel, text: String) {
    val k = LocalKoda.current
    val running = model.working
    var live by remember { mutableStateOf(0L) }
    LaunchedEffect(model.turnStartMs) {
        while (model.working && model.turnStartMs > 0) {
            live = System.currentTimeMillis() - model.turnStartMs
            kotlinx.coroutines.delay(500)
        }
    }
    // Live elapsed while responding; the frozen final elapsed once Done.
    val elapsed = if (running) live else model.lastTurnMs
    val tokens = model.turnOutChars / 4
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(k.surface)
            .border(1.dp, if (running) k.strong else k.border, RoundedCornerShape(9.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        ActivityDot(running)
        Text(if (running) text.ifBlank { "Responding…" } else "Done", fontFamily = Ember.mono, fontSize = 12.sp, color = k.text)
        Sep(); Text("%.1fs".format(elapsed / 1000.0), fontFamily = Ember.mono, fontSize = 12.sp, color = k.dim)
        Sep(); Text("↓%,d tok".format(tokens), fontFamily = Ember.mono, fontSize = 12.sp, color = k.dim)
        if (model.thoughtMs > 0) {
            Sep(); Text("thought %.1fs".format(model.thoughtMs / 1000.0), fontFamily = Ember.mono, fontSize = 12.sp, color = k.faint)
        }
        if (running) {
            Box(
                Modifier.weight(1f).height(2.dp).padding(start = 6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, k.glow, Color.Transparent)))
                    .shimmer(),
            )
        }
    }
}

@Composable
private fun Sep() {
    Text("·", color = LocalKoda.current.faint, fontSize = 12.sp)
}

/** The activity dot — accent + glow + pulse while running, calm ok when done. */
@Composable
private fun ActivityDot(running: Boolean) {
    val k = LocalKoda.current
    Box(
        Modifier.size(7.dp).then(if (running) Modifier.blink() else Modifier)
            .clip(RoundedCornerShape(50)).background(if (running) k.accent else k.ok),
    )
}

@Composable
private fun ToolCard(line: Line.Tool) {
    val k = LocalKoda.current
    val hasDiff = !line.diff.isNullOrBlank()
    // Diffs expand by default (the design shows the edit_file hunk inline).
    var expanded by remember(line.key) { mutableStateOf(hasDiff) }
    val open = hasDiff && expanded
    val shape = if (open) RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp) else RoundedCornerShape(9.dp)
    Column(Modifier.fillMaxWidth().enterUp()) {
        // Head row.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.fillMaxWidth().clip(shape)
                .background(k.surface).border(1.dp, k.border, shape)
                .then(if (hasDiff) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            ToolStatusGlyph(line.status)
            Text(line.name, fontFamily = Ember.mono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = k.text)
            Text(
                line.detail, fontFamily = Ember.mono, fontSize = 12.sp, color = k.accentSoft,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (hasDiff) {
                val adds = line.diff!!.lines().count { it.startsWith("+") && !it.startsWith("+++") }
                val dels = line.diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
                Text("+$adds −$dels", fontFamily = Ember.mono, fontSize = 11.sp, color = k.dim)
                Text(
                    "▸", fontFamily = Ember.mono, fontSize = 11.sp, color = k.faint,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            } else if (line.meta.isNotBlank()) {
                Text(line.meta, fontFamily = Ember.mono, fontSize = 11.sp, color = k.faint)
            }
        }
        // Diff body — attached, with a raised filename + count header bar.
        if (open) {
            val botShape = RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)
            Column(Modifier.fillMaxWidth().clip(botShape).background(k.surface).border(1.dp, k.border, botShape)) {
                val adds = line.diff!!.lines().count { it.startsWith("+") && !it.startsWith("+++") }
                val dels = line.diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
                Row(
                    Modifier.fillMaxWidth().background(k.raised).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(line.detail, fontFamily = Ember.mono, fontSize = 11.sp, color = k.dim, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("+$adds", fontFamily = Ember.mono, fontSize = 11.sp, color = k.diffAddTx)
                    Spacer(Modifier.width(6.dp))
                    Text("−$dels", fontFamily = Ember.mono, fontSize = 11.sp, color = k.diffDelTx)
                }
                HDivider()
                DiffView(line.diff, embedded = true)
            }
        }
    }
}

/** Tool status marker: spinning ring (running), filled ✓ (ok), filled ! (error). */
@Composable
private fun ToolStatusGlyph(status: ToolStatus) {
    val k = LocalKoda.current
    when (status) {
        ToolStatus.Running -> Spinner(15.dp, k.accent)
        ToolStatus.Ok -> Box(Modifier.size(15.dp).clip(RoundedCornerShape(50)).background(k.ok), contentAlignment = Alignment.Center) {
            Text("✓", color = k.onAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        ToolStatus.Error -> Box(Modifier.size(15.dp).clip(RoundedCornerShape(50)).background(k.danger), contentAlignment = Alignment.Center) {
            Text("!", color = k.onAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ApprovalCard(summary: String, onDecide: (ApprovalDecision) -> Unit) {
    val k = LocalKoda.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().enterUp().height(IntrinsicSize.Min).clip(shape)
            .background(k.surface).border(1.dp, k.strong, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(k.accent))
        Row(
            Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(KIcons.shield, contentDescription = null, tint = k.accent, modifier = Modifier.size(16.dp))
            Text(summary, fontSize = 13.sp, color = k.text, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoKeyButton("Y", onDecide, ApprovalDecision.APPROVE, filled = true)
                MonoKeyButton("A", onDecide, ApprovalDecision.APPROVE_ALWAYS)
                MonoKeyButton("N", onDecide, ApprovalDecision.DENY)
            }
        }
    }
}

@Composable
private fun MonoKeyButton(label: String, onDecide: (ApprovalDecision) -> Unit, decision: ApprovalDecision, filled: Boolean = false) {
    val k = LocalKoda.current
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.clip(shape)
            .background(if (filled) k.accent else k.raised)
            .then(if (filled) Modifier else Modifier.border(1.dp, k.border, shape))
            .clickable { onDecide(decision) }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(label, fontFamily = Ember.mono, fontSize = 11.5f.sp, fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal, color = if (filled) k.onAccent else k.text)
    }
}

private val SLASH_CMDS = listOf(
    "new" to "start a fresh session",
    "review" to "review uncommitted changes",
    "plan" to "toggle plan mode",
    "rewind" to "undo the last turn",
    "compact" to "compress history",
    "settings" to "open settings",
)

@Composable
private fun ClarifyCard(c: ClarifyRequest, model: AppModel) {
    val k = LocalKoda.current
    val shape = RoundedCornerShape(11.dp)
    var typing by remember(c.clarifyId) { mutableStateOf(false) }
    var custom by remember(c.clarifyId) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().enterUp(300).clip(shape).background(k.surface).border(1.dp, k.strong, shape),
    ) {
        // Header on the hi-gradient wash.
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(k.hi, Color.Transparent)))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(c.question, fontSize = 13.5f.sp, fontWeight = FontWeight.SemiBold, color = k.text)
            Text("Pick an option or reply in your own words.", fontSize = 12.sp, color = k.dim, modifier = Modifier.padding(top = 2.dp))
        }
        HDivider()
        Column(Modifier.padding(6.dp)) {
            c.options.forEachIndexed { i, opt ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(RoundedCornerShape(8.dp))
                        .clickable { model.answerClarify(opt.label) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(k.raised).border(1.dp, k.border, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", fontFamily = Ember.mono, fontSize = 11.sp, color = k.dim) }
                    Column(Modifier.weight(1f)) {
                        Text(opt.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = k.text)
                        if (opt.description.isNotBlank()) {
                            Text(opt.description, fontSize = 11.5f.sp, color = k.dim, lineHeight = 16.sp)
                        }
                    }
                }
            }
            // Footer: type-something (dashed) + chat-about-this (solid).
            if (!typing) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .border(1.dp, k.border, RoundedCornerShape(8.dp))
                            .clickable { typing = true }.padding(horizontal = 10.dp, vertical = 7.dp),
                    ) { Text("Type something…", fontSize = 12.sp, color = k.dim) }
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, k.border, RoundedCornerShape(8.dp))
                            .clickable { model.answerClarify("Let's talk this through instead of picking one of those options.") }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text("Chat about this", fontSize = 12.sp, color = k.dim) }
                }
            } else {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    EmberField(custom, { custom = it }, "Your answer…", Modifier.weight(1f), onSubmit = { if (custom.isNotBlank()) model.answerClarify(custom) })
                    Spacer(Modifier.width(8.dp))
                    EmberButton("Send", onClick = { if (custom.isNotBlank()) model.answerClarify(custom) })
                }
            }
        }
    }
}

@Composable
private fun Composer(model: AppModel, onOpenSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<String>() }
    fun runSlash(name: String) {
        when (name) {
            "new" -> model.newSession()
            "review" -> model.review("uncommitted")
            "plan" -> model.changeMode(if (model.mode == PermissionModeSetting.PLAN) PermissionModeSetting.DEFAULT else PermissionModeSetting.PLAN)
            "rewind" -> model.rewind(1)
            "compact" -> model.compact()
            "settings" -> onOpenSettings()
        }
    }
    fun submit() {
        val t = input.trim()
        if (t.startsWith("/")) {
            val name = t.drop(1).substringBefore(' ').lowercase()
            if (SLASH_CMDS.any { it.first == name }) { runSlash(name); input = ""; return }
        }
        model.send(input, attachments.toList()); input = ""; attachments.clear()
    }
    val slashQuery = if (input.startsWith("/") && !input.contains(' ')) input.drop(1).lowercase() else null
    val matches = if (slashQuery != null) SLASH_CMDS.filter { it.first.startsWith(slashQuery) } else emptyList()
    val shape = RoundedCornerShape(14.dp)

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // Slash-command autocomplete, above the input.
        if (matches.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp)).padding(4.dp),
            ) {
                matches.forEach { (name, desc) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { runSlash(name); input = "" }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("/$name", fontFamily = Ember.mono, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(90.dp))
                        Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
        }

        // One bordered box: input on top, controls toolbar inside at the bottom
        // — matching the handoff's single composer surface.
        val k = LocalKoda.current
        Column(
            Modifier.fillMaxWidth().clip(shape).background(k.surface).border(1.dp, k.strong, shape),
        ) {
            if (attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
                    attachments.forEach { a ->
                        Box(
                            Modifier.clip(ChipShape).border(1.dp, k.border, ChipShape)
                                .clickable { attachments.remove(a) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("${a.substringAfterLast('/')}  ✕", fontSize = 11.sp, fontFamily = Ember.mono, color = k.dim)
                        }
                    }
                }
            }
            EmberField(
                value = input, onValueChange = { input = it },
                placeholder = "Ask Koda to build, fix, or explain…    /  for commands",
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), bordered = false, onSubmit = ::submit,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModeMenu(model)
                if (!model.working) ModelMenu(model)
                Spacer(Modifier.weight(1f))
                if (model.working) {
                    // Mid-stream: only Stop (model/attach hidden), matching the design.
                    EmberButton("Stop", onClick = { model.interrupt() })
                } else {
                    AttachButton { attachments.add(it) }
                    EmberButton("Send  →", onClick = ::submit, enabled = input.isNotBlank() || attachments.isNotEmpty())
                }
            }
        }
    }
}

@Composable
private fun AttachButton(onPick: (String) -> Unit) {
    Box(
        Modifier.clip(ChipShape).border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
            .clickable {
                val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Attach image", java.awt.FileDialog.LOAD)
                dialog.isVisible = true
                val f = dialog.file; val dir = dialog.directory
                if (f != null && dir != null) onPick(java.io.File(dir, f).absolutePath)
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text("📎", fontSize = 13.sp)
    }
}

@Composable
private fun ModeMenu(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val label = when (model.mode) {
        PermissionModeSetting.DEFAULT -> "default"
        PermissionModeSetting.ACCEPT_EDITS -> "accept-edits"
        PermissionModeSetting.YOLO -> "yolo"
        PermissionModeSetting.PLAN -> "plan"
    }
    Box {
        Row(
            Modifier.clip(ChipShape).border(1.dp, MaterialTheme.colorScheme.primary, ChipShape)
                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.primary)
            Text(" ▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                PermissionModeSetting.DEFAULT to "default",
                PermissionModeSetting.ACCEPT_EDITS to "accept-edits",
                PermissionModeSetting.YOLO to "yolo",
                PermissionModeSetting.PLAN to "plan",
            ).forEach { (m, l) ->
                DropdownMenuItem(text = { Text(l, fontFamily = Ember.mono, fontSize = 12.5f.sp) }, onClick = { model.changeMode(m); open = false })
            }
        }
    }
}

@Composable
private fun ModelMenu(model: AppModel) {
    var open by remember { mutableStateOf(false) }
    val label = model.activeModel.ifBlank { model.modelName.ifBlank { "model" } }
    Box {
        Row(
            Modifier.clip(ChipShape).border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
                .clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(" ▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (model.availableModels.isEmpty()) {
                DropdownMenuItem(text = { Text("(no models)") }, onClick = { open = false })
            }
            model.availableModels.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id, fontFamily = Ember.mono, fontSize = 12.5f.sp, color = if (id == model.activeModel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { model.setModelId(id); open = false },
                )
            }
        }
    }
}
