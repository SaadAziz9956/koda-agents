package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.Ember
import dev.koda.protocol.DirEntry

/** Workspace file tree, served by the daemon over ListDir/GetFile. */
@Composable
fun FileExplorer(model: AppModel) {
    val root = model.dirCache[""] ?: emptyList()
    LazyColumn(Modifier.fillMaxSize()) {
        renderNodes(this, model, root, 0)
    }
}

private fun renderNodes(scope: androidx.compose.foundation.lazy.LazyListScope, model: AppModel, entries: List<DirEntry>, depth: Int) {
    for (entry in entries) {
        scope.item(key = entry.path) { TreeRow(model, entry, depth) }
        if (entry.isDir && model.expandedDirs.contains(entry.path)) {
            val children = model.dirCache[entry.path]
            if (children == null) scope.item(key = "${entry.path}::loading") { LoadingRow(depth + 1) }
            else renderNodes(scope, model, children, depth + 1)
        }
    }
}

@Composable
private fun TreeRow(model: AppModel, entry: DirEntry, depth: Int) {
    val open = model.expandedDirs.contains(entry.path)
    val isCurrent = model.openFilePath == entry.path
    Row(
        Modifier.fillMaxWidth()
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { if (entry.isDir) model.toggleDir(entry.path) else model.openFile(entry.path) }
            .padding(start = (8 + depth * 13).dp, top = 3.dp, bottom = 3.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.isDir) (if (open) "▾" else "▸") else " ",
            fontFamily = Ember.mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(14.dp),
        )
        Text(
            entry.name,
            fontFamily = Ember.mono, fontSize = 12.5f.sp,
            fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
            else if (entry.isDir) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingRow(depth: Int) {
    Text("…", fontFamily = Ember.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = (8 + depth * 13).dp, top = 3.dp, bottom = 3.dp))
}

/** Modal file viewer (mono, scrollable). Opened from the tree. */
@Composable
fun FileViewer(model: AppModel) {
    val path = model.openFilePath ?: return
    val vscroll = rememberScrollState()
    val hscroll = rememberScrollState()
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.closeFile() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.85f).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(path, fontFamily = Ember.mono, fontSize = 12.5f.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                EmberTextButton("Close", onClick = { model.closeFile() })
            }
            HDivider()
            val err = model.openFileError
            if (err != null) {
                Text("cannot open: $err", color = MaterialTheme.colorScheme.error, fontFamily = Ember.mono, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            } else {
                Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(vscroll)) {
                    Column(Modifier.horizontalScroll(hscroll)) {
                        Text(model.openFileContent, fontFamily = Ember.mono, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}
