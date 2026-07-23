package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.Ember
import dev.koda.protocol.PermissionModeSetting

@Composable
fun SettingsScreen(model: AppModel, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)).padding(24.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                EmberTextButton("Done", onClick = onClose)
            }
            Spacer(Modifier.size(14.dp))

            Section("Connection")
            FieldRow("Daemon address", model.daemonUrl)
            FieldRow("Status", model.status.name.lowercase())

            Spacer(Modifier.size(16.dp))
            Section("Permissions")
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Default mode", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Segmented(model.mode) { model.changeMode(it) }
            }

            Spacer(Modifier.size(16.dp))
            Section("Model")
            FieldRow("Model", model.modelName.ifBlank { "—" })
            FieldRow("Provider", model.providerName.ifBlank { "—" })
            var newModel by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Switch model", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                EmberField(newModel, { newModel = it }, "model id", Modifier.width(180.dp), mono = true)
                Spacer(Modifier.width(8.dp))
                EmberButton("Set", onClick = { model.setModelId(newModel); newModel = "" })
            }

            Spacer(Modifier.size(16.dp))
            Section("Scheduled tasks")
            model.schedules.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.prompt, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text("every ${s.everySeconds}s", fontSize = 11.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    EmberTextButton("Cancel", onClick = { model.cancelSchedule(s.id) }, danger = true)
                }
            }
            if (model.schedules.isEmpty()) Text("none", fontSize = 12.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            var schedPrompt by remember { mutableStateOf("") }
            var schedEvery by remember { mutableStateOf("3600") }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                EmberField(schedPrompt, { schedPrompt = it }, "prompt to run…", Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                EmberField(schedEvery, { schedEvery = it }, "secs", Modifier.width(72.dp), mono = true)
                Spacer(Modifier.width(8.dp))
                EmberButton("Add", onClick = {
                    val secs = schedEvery.toLongOrNull() ?: 3600
                    if (schedPrompt.isNotBlank()) { model.createSchedule(schedPrompt, secs); schedPrompt = "" }
                })
            }

            Spacer(Modifier.size(16.dp))
            Section("Appearance")
            FieldRow("Theme", "follows system")
            FieldRow("Accent", "Ember")
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun FieldRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.5f.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Segmented(mode: PermissionModeSetting, onPick: (PermissionModeSetting) -> Unit) {
    val opts = listOf(
        PermissionModeSetting.DEFAULT to "default",
        PermissionModeSetting.ACCEPT_EDITS to "accept-edits",
        PermissionModeSetting.YOLO to "yolo",
    )
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.background).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        opts.forEach { (m, label) ->
            val on = m == mode
            Box(
                Modifier.clip(RoundedCornerShape(7.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onPick(m) }.padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(label, fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
