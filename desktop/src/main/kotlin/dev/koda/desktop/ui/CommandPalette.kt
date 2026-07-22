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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

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
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Search commands, sessions, skills…") },
                singleLine = true,
                keyboardActions = KeyboardActions(onAny = { filtered.firstOrNull()?.let(::runAndClose) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            HDivider()
            LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.group + it.label }) { it ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable { runAndClose(it) }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(it.label, fontSize = 13.5f.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
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
