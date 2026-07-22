package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.Ember
import dev.koda.protocol.CheckpointInfo

@Composable
fun RewindPanel(model: AppModel, modifier: Modifier = Modifier) {
    var selected by remember { mutableStateOf<Int?>(null) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⏪  Rewind", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text("${model.checkpoints.size}", fontSize = 10.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HDivider()
        Text(
            "Every turn is a restore point — conversation and the files it changed.",
            fontSize = 11.5f.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(model.checkpoints, key = { it.index }) { cp ->
                CheckpointRow(cp, isTarget = selected == cp.index) { selected = cp.index }
            }
        }
        HDivider()
        Column(Modifier.padding(14.dp)) {
            val target = selected
            if (target == null) {
                Button(onClick = { if (model.checkpoints.isNotEmpty()) selected = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Rewind last turn")
                }
            } else {
                Text("Rewind $target turn(s)?", fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.rewind(target); selected = null }, modifier = Modifier.weight(1f)) {
                        Text("Rewind")
                    }
                    TextButton(onClick = { selected = null }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun CheckpointRow(cp: CheckpointInfo, isTarget: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val now = cp.index == 1
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .then(if (isTarget) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (now || isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, 9.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(cp.label.ifBlank { "(turn ${cp.index})" }, fontSize = 12.5f.sp, fontWeight = FontWeight.Medium,
                color = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(
                (if (now) "now" else "${cp.index} turns back") + " · ${cp.fileCount} file(s)",
                fontSize = 10.sp, fontFamily = Ember.mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
