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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.Geist
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.LocalKoda

private data class PaletteItem(
    val group: String, val icon: String, val label: String, val hint: String,
    val vector: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val run: () -> Unit,
)

/**
 * ⌘K command palette — a warm-scrim overlay with a fixed 560×440 card, grouped
 * (Navigate / Commands) rows with icon glyphs + shortcut hints, and a footer.
 * Built 1:1 with the Figma node (23759:15054).
 */
@Composable
fun CommandPalette(model: AppModel, onClose: () -> Unit, onNavigate: (AppView) -> Unit) {
    val k = LocalKoda.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) { selected = 0 }

    val items = buildList {
        add(PaletteItem("Navigate", "⌂", "Go to Home chat", "view") { onNavigate(AppView.Home) })
        add(PaletteItem("Navigate", "{}", "Open Code workspace", "view") { onNavigate(AppView.Code) })
        add(PaletteItem("Navigate", "⇄", "Connect to daemon…", "view") { onNavigate(AppView.Connect) })
        add(PaletteItem("Navigate", "", "Open Settings", "⌘,", vector = KIcons.gear) { onNavigate(AppView.Settings) })
        add(PaletteItem("Commands", "+", "New session", "⌘N") { model.newSession() })
        add(PaletteItem("Commands", "↺", "Rewind last turn", "") { model.rewind(1) })
    }
    val filtered = items.filter { query.isBlank() || it.label.contains(query, true) }
    fun runAndClose(it: PaletteItem) { it.run(); onClose() }

    val indexed = filtered.withIndex().toList()
    val groups = LinkedHashMap<String, MutableList<IndexedValue<PaletteItem>>>()
    indexed.forEach { groups.getOrPut(it.value.group) { mutableListOf() }.add(it) }

    // Warm translucent scrim (English Walnut 28%), matching the design.
    Box(
        Modifier.fillMaxSize().background(Color(0x473C2D23))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.padding(top = 96.dp).width(560.dp).height(440.dp).clip(RoundedCornerShape(14.dp))
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
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 15.dp),
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
            // List.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
                groups.forEach { (group, rows) ->
                    item(key = "h:$group") {
                        Text(
                            group.uppercase(), fontFamily = Geist, fontSize = 10.sp, letterSpacing = 0.8.sp,
                            fontWeight = FontWeight.SemiBold, color = k.faint,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                        )
                    }
                    rows.forEach { (gi, it) ->
                        item(key = "$group:${it.label}") {
                            val on = gi == selected
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .then(if (on) Modifier.background(k.sel).border(1.dp, k.accent, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable { runAndClose(it) }
                                    .padding(horizontal = if (on) 13.dp else 12.dp, vertical = if (on) 10.dp else 9.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                if (it.vector != null) {
                                    Icon(it.vector, contentDescription = null, tint = k.dim, modifier = Modifier.size(16.dp))
                                } else {
                                    Text(it.icon, fontFamily = Geist, fontSize = 16.sp, color = k.dim, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
                                }
                                Text(it.label, fontFamily = Geist, fontSize = 13.5f.sp, color = k.text, modifier = Modifier.weight(1f), maxLines = 1)
                                if (it.hint.isNotBlank()) Text(it.hint, fontFamily = JetBrainsMono, fontSize = 10.5f.sp, color = k.faint)
                            }
                        }
                    }
                }
            }
            HDivider()
            // Footer.
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                FooterHint("↑↓", "navigate"); FooterHint("↵", "open"); FooterHint("esc", "close")
            }
        }
    }
}

@Composable
private fun Kbd(text: String) {
    val k = LocalKoda.current
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = k.faint)
    }
}

@Composable
private fun FooterHint(symbol: String, label: String) {
    val k = LocalKoda.current
    Row {
        Text(symbol, fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.faint)
        Text(" $label", fontFamily = Geist, fontSize = 11.sp, color = k.faint)
    }
}
