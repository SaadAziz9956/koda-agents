package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.Ember
import dev.koda.protocol.GitFileChange

@Composable
fun GitPanel(model: AppModel, modifier: Modifier = Modifier) {
    var message by remember { mutableStateOf("") }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (model.gitOk) {
                Text(model.gitBranch, fontFamily = Ember.mono, fontSize = 12.5f.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (model.gitAhead > 0 || model.gitBehind > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("↑${model.gitAhead} ↓${model.gitBehind}", fontFamily = Ember.mono, fontSize = 10.5f.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("not a git repo", fontFamily = Ember.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            EmberTextButton("Refresh", onClick = { model.refreshGit() })
        }
        HDivider()

        if (model.gitOk) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(model.gitFiles, key = { it.path }) { f -> GitFileRow(f, model.gitDiffPath == f.path) { model.showGitDiff(f.path) } }
                if (model.gitFiles.isEmpty()) item { Text("working tree clean", fontFamily = Ember.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp)) }
            }
            if (model.gitDiff.isNotBlank()) {
                HDivider()
                Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) { DiffView(model.gitDiff, maxHeight = 260) }
            }
            HDivider()
            Column(Modifier.padding(12.dp)) {
                EmberField(message, { message = it }, "Commit message…", Modifier.fillMaxWidth())
                Spacer(Modifier.width(8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EmberButton("Commit", onClick = { if (message.isNotBlank()) { model.gitCommit(message); message = "" } }, modifier = Modifier.weight(1f))
                    EmberGhostButton("PR", onClick = { model.createPr(model.gitBranch.ifBlank { "Koda changes" }) })
                }
            }
        }
    }
}

@Composable
private fun GitFileRow(f: GitFileChange, selected: Boolean, onClick: () -> Unit) {
    val color = when (f.status) {
        "added" -> Ember.ok
        "deleted" -> MaterialTheme.colorScheme.error
        "untracked" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(f.status.first().uppercase(), fontFamily = Ember.mono, fontSize = 11.sp, color = color, modifier = Modifier.width(16.dp))
        Text(f.path, fontFamily = Ember.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}
