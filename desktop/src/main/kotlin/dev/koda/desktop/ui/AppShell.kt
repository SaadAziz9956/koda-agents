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
import androidx.compose.ui.draw.clip
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
import dev.koda.desktop.theme.Ember
import dev.koda.protocol.ApprovalDecision
import dev.koda.protocol.ClarifyRequest
import dev.koda.protocol.PermissionModeSetting

enum class AppView { Home, Code }

@Composable
fun AppShell(model: AppModel, onOpenPalette: () -> Unit, onOpenSettings: () -> Unit) {
    var view by remember { mutableStateOf(AppView.Code) }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Rail(model, view, { view = it }, Modifier.width(230.dp).fillMaxHeight())
        VDivider()
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(model, onOpenPalette, onOpenSettings)
            HDivider()
            if (model.status != ConnStatus.Connected) ReconnectBanner(model)
            Box(Modifier.weight(1f).fillMaxWidth()) { Transcript(model) }
            HDivider()
            Composer(model, onOpenSettings)
        }
        // The coding workspace (Files/Git/Rewind) only shows in Code view.
        if (view == AppView.Code) {
            VDivider()
            ContextPanel(model, Modifier.width(320.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun ContextPanel(model: AppModel, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth()) {
            TabButton("Rewind", tab == 0) { tab = 0 }
            TabButton("Files", tab == 1) { tab = 1 }
            TabButton("Git", tab == 2) { tab = 2 }
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
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
private fun TopBar(model: AppModel, onOpenPalette: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Koda", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("session ${model.sessionId}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            val sub = listOf(model.modelName, model.providerName).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (model.working) {
            Text("working…", fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Chip("⌘K", onClick = onOpenPalette)
        Spacer(Modifier.width(10.dp))
        ContextRing(model.contextPercent)
        Spacer(Modifier.width(7.dp))
        Text(model.contextPercent?.let { "$it%" } ?: "—", fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Chip("⚙", onClick = onOpenSettings)
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
    LaunchedEffect(total, model.streaming, model.reasoning, model.activity) {
        if (total > 0 && atBottom) state.scrollToItem(total - 1)
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(vertical = 22.dp),
    ) {
        items(model.lines, key = { it.key }) { line -> LineView(line) }
        if (model.reasoning.isNotBlank()) item(key = -3) { ReasoningBlock(model.reasoning) }
        if (model.streaming.isNotBlank()) item(key = -1) { StreamingLine(model.streaming) }
        if (model.working && model.streaming.isBlank() && model.reasoning.isBlank()) {
            item(key = -4) { ActivityLine(model, model.activity ?: "Working…") }
        }
        model.pendingApproval?.let { req -> item(key = -2) { ApprovalCard(req.summary) { model.approve(it) } } }
        model.pendingClarify?.let { c -> item(key = -5) { ClarifyCard(c, model) } }
    }
}

@Composable
private fun LineView(line: Line) {
    when (line) {
        is Line.User -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .padding(13.dp),
        ) {
            Text("YOU", fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(6.dp))
            Text(line.text, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        is Line.Assistant -> Column(Modifier.fillMaxWidth()) {
            Text("KODA", fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            MarkdownText(line.text)
        }
        is Line.Tool -> ToolCard(line)
        is Line.Note -> Text(
            line.text,
            color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = Ember.mono, fontSize = 12.sp,
        )
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
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(model.turnStartMs) {
        while (model.working && model.turnStartMs > 0) {
            elapsed = System.currentTimeMillis() - model.turnStartMs
            kotlinx.coroutines.delay(500)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().shimmer()) {
        Dot(MaterialTheme.colorScheme.primary, 7.dp)
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        val tokens = model.turnOutChars / 4
        val meta = buildString {
            append("${elapsed / 1000}s")
            if (tokens > 0) append("  ·  ↓${tokens} tok")
            if (model.thoughtMs > 0) append("  ·  thought ${model.thoughtMs / 1000}s")
        }
        Text(meta, fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}

@Composable
private fun ToolCard(line: Line.Tool) {
    val shape = RoundedCornerShape(11.dp)
    val hasDiff = !line.diff.isNullOrBlank()
    var expanded by remember(line.key) { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surface)
            .then(if (line.status == ToolStatus.Running) Modifier.shimmer() else Modifier)
            .padding(11.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (hasDiff) Modifier.fillMaxWidth().clickable { expanded = !expanded } else Modifier.fillMaxWidth(),
        ) {
            val (glyph, color) = when (line.status) {
                ToolStatus.Running -> "◐" to MaterialTheme.colorScheme.primary
                ToolStatus.Ok -> "✓" to Ember.ok
                ToolStatus.Error -> "✗" to MaterialTheme.colorScheme.error
            }
            Text(glyph, color = color, fontFamily = Ember.mono, fontSize = 13.sp)
            Spacer(Modifier.width(9.dp))
            Text(line.name, fontFamily = Ember.mono, fontWeight = FontWeight.Bold, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(9.dp))
            Text(line.detail, fontFamily = Ember.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
            if (hasDiff) {
                val adds = line.diff!!.lines().count { it.startsWith("+") && !it.startsWith("+++") }
                val dels = line.diff.lines().count { it.startsWith("-") && !it.startsWith("---") }
                Spacer(Modifier.width(8.dp))
                Text("+$adds", color = Ember.ok, fontFamily = Ember.mono, fontSize = 11.sp)
                Spacer(Modifier.width(5.dp))
                Text("−$dels", color = MaterialTheme.colorScheme.error, fontFamily = Ember.mono, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        if (hasDiff && expanded) {
            Spacer(Modifier.size(8.dp))
            DiffView(line.diff!!)
        }
    }
}

@Composable
private fun ApprovalCard(summary: String, onDecide: (ApprovalDecision) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.primary, shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(14.dp),
    ) {
        Text("APPROVAL NEEDED", fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(summary, fontFamily = Ember.mono, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmberButton("Allow  Y", onClick = { onDecide(ApprovalDecision.APPROVE) })
            EmberGhostButton("Always  A", onClick = { onDecide(ApprovalDecision.APPROVE_ALWAYS) })
            EmberTextButton("Deny  N", onClick = { onDecide(ApprovalDecision.DENY) }, danger = true)
        }
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
    val shape = RoundedCornerShape(12.dp)
    var typing by remember(c.clarifyId) { mutableStateOf(false) }
    var custom by remember(c.clarifyId) { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.primary, shape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)).padding(15.dp),
    ) {
        Text("NEEDS INPUT", fontSize = 10.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(c.question, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.size(12.dp))
        c.options.forEachIndexed { i, opt ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { model.answerClarify(opt.label) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text("${i + 1}", fontFamily = Ember.mono, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                Column {
                    Text(opt.label, fontSize = 13.5f.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    if (opt.description.isNotBlank()) {
                        Text(opt.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                    }
                }
            }
        }
        Spacer(Modifier.size(6.dp)); HDivider(); Spacer(Modifier.size(6.dp))
        if (!typing) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { typing = true }.padding(horizontal = 8.dp, vertical = 8.dp)) {
                Text("✎  Type something else", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberField(custom, { custom = it }, "Your answer…", Modifier.weight(1f), onSubmit = { if (custom.isNotBlank()) model.answerClarify(custom) })
                Spacer(Modifier.width(8.dp))
                EmberButton("Send", onClick = { if (custom.isNotBlank()) model.answerClarify(custom) })
            }
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { model.answerClarify("Let's talk this through instead of picking one of those options.") }.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text("💬  Chat about this", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

        // Clean input box — writing only.
        Column(
            Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    attachments.forEach { a ->
                        Box(
                            Modifier.clip(ChipShape).border(1.dp, MaterialTheme.colorScheme.outline, ChipShape)
                                .clickable { attachments.remove(a) }.padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("📎 ${a.substringAfterLast('/')}  ✕", fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            EmberField(
                value = input, onValueChange = { input = it },
                placeholder = "Message Koda…    / for commands",
                modifier = Modifier.fillMaxWidth(), bordered = false, onSubmit = ::submit,
            )
        }
        Spacer(Modifier.size(8.dp))
        // Controls toolbar — below the input, out of the way.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeMenu(model)
            ModelMenu(model)
            AttachButton { attachments.add(it) }
            Spacer(Modifier.weight(1f))
            if (model.working) {
                EmberGhostButton("Stop", onClick = { model.interrupt() }, danger = true)
            }
            EmberButton("Send", onClick = ::submit, enabled = input.isNotBlank() || attachments.isNotEmpty())
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
