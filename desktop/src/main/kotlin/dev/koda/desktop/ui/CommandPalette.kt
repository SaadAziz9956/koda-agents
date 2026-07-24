package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.LocalKoda
import dev.koda.protocol.PermissionModeSetting

private data class PaletteItem(val group: String, val icon: String, val label: String, val hint: String, val run: () -> Unit)

@Composable
fun CommandPalette(model: AppModel, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    val k = LocalKoda.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { selected = 0 }

    val items = buildList {
        add(PaletteItem("Commands", "+", "New session", "⌘N") { model.newSession() })
        add(PaletteItem("Commands", "/", "Review uncommitted changes", "/review") { model.review("uncommitted") })
        add(PaletteItem("Commands", "◇", "Toggle plan mode", "/plan") {
            model.changeMode(if (model.mode == PermissionModeSetting.PLAN) PermissionModeSetting.DEFAULT else PermissionModeSetting.PLAN)
        })
        add(PaletteItem("Commands", "↺", "Rewind last turn", "") { model.rewind(1) })
        add(PaletteItem("Commands", "▤", "Compact history", "/compact") { model.compact() })
        add(PaletteItem("Navigate", "⚙", "Open Settings", "⌘,") { onOpenSettings() })
        model.sessions.forEach { s -> add(PaletteItem("Sessions", "#", s.id, "${s.messageCount} msgs") { model.resume(s.id) }) }
    }
    val filtered = items.filter { query.isBlank() || it.label.contains(query, true) || it.hint.contains(query, true) }
    fun runAndClose(it: PaletteItem) { it.run(); onClose() }

    // Group in first-seen order, tagging each item with its global (selectable) index.
    val indexed = filtered.withIndex().toList()
    val groups = LinkedHashMap<String, MutableList<IndexedValue<PaletteItem>>>()
    indexed.forEach { groups.getOrPut(it.value.group) { mutableListOf() }.add(it) }

    Box(
        Modifier.fillMaxSize().background(Color(0x800A0908))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 96.dp).width(560.dp).heightIn(max = 520.dp).clip(RoundedCornerShape(14.dp))
                .background(k.raised).border(1.dp, k.strong, RoundedCornerShape(14.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { selected = (selected + 1).coerceAtMost((filtered.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                        Key.Enter -> { filtered.getOrNull(selected)?.let(::runAndClose); true }
                        else -> false
                    }
                },
        ) {
            // Search header.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(KIcons.search, contentDescription = null, tint = k.dim, modifier = Modifier.size(16.dp))
                EmberField(
                    value = query, onValueChange = { query = it },
                    placeholder = "Type a command, session, or skill…",
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    bordered = false,
                    onSubmit = { filtered.getOrNull(selected)?.let(::runAndClose) },
                )
                Kbd("esc")
            }
            HDivider()
            LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(6.dp)) {
                groups.forEach { (group, rows) ->
                    item(key = "h:$group") {
                        Text(group.uppercase(), fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = k.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).padding(top = 4.dp))
                    }
                    rows.forEach { (gi, it) ->
                        item(key = "${group}:${it.label}") {
                            val on = gi == selected
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (on) k.sel else Color.Transparent)
                                    .then(if (on) Modifier.border(1.dp, k.accent, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable { runAndClose(it) }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                Text(it.icon, fontFamily = JetBrainsMono, fontSize = 13.sp, color = k.dim, modifier = Modifier.width(16.dp))
                                Text(it.label, fontSize = 13.5f.sp, color = k.text, modifier = Modifier.weight(1f), maxLines = 1)
                                if (it.hint.isNotBlank()) Text(it.hint, fontFamily = JetBrainsMono, fontSize = 10.5f.sp, color = k.faint)
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) item { Text("No matches.", fontSize = 12.5f.sp, color = k.faint, modifier = Modifier.padding(16.dp)) }
            }
            HDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                FooterHint("↑↓", "navigate"); FooterHint("↵", "open"); FooterHint("esc", "close")
                Spacer(Modifier.weight(1f))
                Text("${filtered.size} results", fontSize = 11.sp, fontFamily = JetBrainsMono, color = k.faint)
            }
        }
    }
}

@Composable
private fun Kbd(text: String) {
    val k = LocalKoda.current
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = k.faint)
    }
}

@Composable
private fun FooterHint(key: String, label: String) {
    val k = LocalKoda.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(key, fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.dim)
        Text(label, fontSize = 11.sp, color = k.faint)
    }
}
