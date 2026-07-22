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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import dev.koda.protocol.PermissionModeSetting

@Composable
fun AppShell(model: AppModel, onOpenPalette: () -> Unit, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Rail(model, Modifier.width(230.dp).fillMaxHeight())
        VDivider()
        Column(Modifier.weight(1f).fillMaxHeight()) {
            TopBar(model, onOpenPalette, onOpenSettings)
            HDivider()
            if (model.status != ConnStatus.Connected) ReconnectBanner(model)
            Box(Modifier.weight(1f).fillMaxWidth()) { Transcript(model) }
            HDivider()
            Composer(model)
        }
        VDivider()
        RewindPanel(model, Modifier.width(300.dp).fillMaxHeight())
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
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 30.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        contentPadding = PaddingValues(vertical = 22.dp),
    ) {
        items(model.lines, key = { it.key }) { line -> LineView(line) }
        if (model.streaming.isNotBlank()) item(key = -1) { StreamingLine(model.streaming) }
        model.pendingApproval?.let { req -> item(key = -2) { ApprovalCard(req.summary) { model.approve(it) } } }
    }
}

@Composable
private fun LineView(line: Line) {
    when (line) {
        is Line.User -> Column {
            Text("YOU", fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(5.dp))
            Text(line.text, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
        is Line.Assistant -> Text(line.text, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
        is Line.Tool -> ToolCard(line)
        is Line.Note -> Text(
            line.text,
            color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = Ember.mono, fontSize = 12.sp,
        )
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
private fun ToolCard(line: Line.Tool) {
    val shape = RoundedCornerShape(11.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surface).padding(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (glyph, color) = when (line.status) {
                ToolStatus.Running -> "◐" to MaterialTheme.colorScheme.primary
                ToolStatus.Ok -> "✓" to Ember.ok
                ToolStatus.Error -> "✗" to MaterialTheme.colorScheme.error
            }
            Text(glyph, color = color, fontFamily = Ember.mono, fontSize = 13.sp)
            Spacer(Modifier.width(9.dp))
            Text(line.name, fontFamily = Ember.mono, fontWeight = FontWeight.Bold, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(9.dp))
            Text(line.detail, fontFamily = Ember.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
            Button(onClick = { onDecide(ApprovalDecision.APPROVE) }) { Text("Allow  Y") }
            OutlinedButton(onClick = { onDecide(ApprovalDecision.APPROVE_ALWAYS) }) { Text("Always  A") }
            TextButton(onClick = { onDecide(ApprovalDecision.DENY) }) { Text("Deny  N", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun Composer(model: AppModel) {
    var input by remember { mutableStateOf("") }
    fun submit() { model.send(input); input = "" }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Koda…") },
                singleLine = true,
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            ModeChip(model)
            Button(onClick = ::submit, enabled = input.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun ModeChip(model: AppModel) {
    val next = when (model.mode) {
        PermissionModeSetting.DEFAULT -> PermissionModeSetting.ACCEPT_EDITS
        PermissionModeSetting.ACCEPT_EDITS -> PermissionModeSetting.YOLO
        PermissionModeSetting.YOLO -> PermissionModeSetting.DEFAULT
    }
    val label = when (model.mode) {
        PermissionModeSetting.DEFAULT -> "default"
        PermissionModeSetting.ACCEPT_EDITS -> "accept-edits"
        PermissionModeSetting.YOLO -> "yolo"
    }
    Box(
        Modifier.clip(ChipShape).border(1.dp, MaterialTheme.colorScheme.primary, ChipShape)
            .clickable { model.changeMode(next) }.padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.primary)
    }
}
