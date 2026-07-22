package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.ConnStatus
import dev.koda.desktop.Line
import dev.koda.desktop.theme.Ember

@Composable
fun AppShell(model: AppModel) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopBar(model)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Transcript(model)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Composer(model)
    }
}

@Composable
private fun TopBar(model: AppModel) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Koda", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            val (label, color) = when (model.status) {
                ConnStatus.Connected -> "connected" to Ember.ok
                ConnStatus.Connecting -> "connecting…" to MaterialTheme.colorScheme.primary
                ConnStatus.Disconnected -> "offline" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(7.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Ember.mono)
            Spacer(Modifier.size(14.dp))
            Text(model.sessionId, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = Ember.mono)
        }
    }
}

@Composable
private fun Transcript(model: AppModel) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 22.dp),
    ) {
        items(model.lines, key = { it.key }) { line -> LineView(line) }
        if (model.streaming.isNotBlank()) {
            item(key = -1) {
                Text(
                    model.streaming,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 14.5f.sp,
                )
            }
        }
    }
}

@Composable
private fun LineView(line: Line) {
    when (line) {
        is Line.User -> Text(
            line.text,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            fontSize = 14.5f.sp,
        )
        is Line.Assistant -> Text(
            line.text,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.5f.sp,
        )
        is Line.Tool -> Text(
            line.text,
            color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = Ember.mono,
            fontSize = 12.5f.sp,
        )
        is Line.Note -> Text(
            line.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = Ember.mono,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Composer(model: AppModel) {
    var input by remember { mutableStateOf("") }
    fun submit() { model.send(input); input = "" }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Koda…") },
                singleLine = true,
                keyboardActions = KeyboardActions(onSend = { submit() }),
            )
            Button(onClick = ::submit, enabled = input.isNotBlank()) { Text("Send") }
        }
    }
}
