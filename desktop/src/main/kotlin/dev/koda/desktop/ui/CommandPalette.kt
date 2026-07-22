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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.Ember

private data class PaletteItem(val group: String, val label: String, val hint: String, val run: () -> Unit)

@Composable
fun CommandPalette(model: AppModel, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { selected = 0 }

    val items = buildList {
        add(PaletteItem("Commands", "New session", "/new") { model.newSession() })
        add(PaletteItem("Commands", "Rewind last turn", "/rewind") { model.rewind(1) })
        add(PaletteItem("Commands", "Compact history", "/compact") { model.compact() })
        add(PaletteItem("Commands", "Settings", "⚙") { onOpenSettings() })
        model.sessions.forEach { s -> add(PaletteItem("Sessions", s.id, "${s.messageCount} msgs") { model.resume(s.id) }) }
    }
    val filtered = items.filter { query.isBlank() || it.label.contains(query, true) || it.hint.contains(query, true) }
    fun runAndClose(it: PaletteItem) { it.run(); onClose() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.padding(top = 90.dp).width(560.dp).clip(RoundedCornerShape(15.dp))
                .background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(15.dp))
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
            EmberField(
                value = query, onValueChange = { query = it },
                placeholder = "Search commands, sessions, skills…",
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                bordered = false,
                onSubmit = { filtered.getOrNull(selected)?.let(::runAndClose) },
            )
            HDivider()
            LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(filtered, key = { _, it -> it.group + it.label }) { index, it ->
                    val on = index == selected
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { runAndClose(it) }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(it.label, fontSize = 13.5f.sp, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Text(it.hint, fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("↵ run", fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("esc close", fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("${filtered.size} results", fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
