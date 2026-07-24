package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.KodaColors
import dev.koda.desktop.theme.LocalKoda
import dev.koda.protocol.DirEntry

/** Workspace file tree, served by the daemon over ListDir/GetFile. */
@Composable
fun FileExplorer(model: AppModel) {
    val root = model.dirCache[""] ?: emptyList()
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp, horizontal = 4.dp)) {
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

/** File-type accent for the little square marker, following the handoff palette. */
private fun typeColor(name: String, k: KodaColors): Color {
    if (name.contains(".test.") || name.contains(".spec.")) return k.ok
    return when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts", "ts", "tsx", "js", "jsx", "java" -> k.info
        "json", "yaml", "yml", "toml", "gradle" -> k.synNumber
        "md", "txt", "rst" -> k.dim
        "env" -> k.danger
        else -> k.info
    }
}

@Composable
private fun TreeRow(model: AppModel, entry: DirEntry, depth: Int) {
    val k = LocalKoda.current
    val open = model.expandedDirs.contains(entry.path)
    val isCurrent = model.openFilePath == entry.path
    val badge = if (!entry.isDir) model.gitFiles.firstOrNull { it.path == entry.path || it.path.endsWith("/" + entry.name) } else null
    Row(
        Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(7.dp))
            .background(if (isCurrent) k.surface else Color.Transparent)
            .clickable { if (entry.isDir) model.toggleDir(entry.path) else model.openFile(entry.path) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Indent guides — one thin rule per ancestor level.
        repeat(depth) {
            Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.padding(start = 6.dp).width(1.dp).fillMaxHeight().background(k.border))
            }
        }
        if (entry.isDir) {
            Text("▸", fontFamily = JetBrainsMono, fontSize = 10.sp, color = k.faint, modifier = Modifier.width(14.dp).rotate(if (open) 90f else 0f))
            Icon(KIcons.folder, contentDescription = null, tint = k.accentSoft, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
        } else {
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(typeColor(entry.name, k)))
            Spacer(Modifier.width(9.dp))
        }
        Text(
            entry.name,
            fontFamily = JetBrainsMono, fontSize = 12.sp,
            fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
            color = k.text, modifier = Modifier.weight(1f), maxLines = 1,
        )
        if (badge != null) {
            val (glyph, color) = when (badge.status) {
                "added" -> "A" to k.diffAddTx
                "deleted" -> "D" to k.diffDelTx
                "untracked" -> "?" to k.faint
                else -> "M" to k.diffAddTx
            }
            Text(glyph, fontFamily = JetBrainsMono, fontSize = 10.sp, color = color)
        }
    }
}

@Composable
private fun LoadingRow(depth: Int) {
    val k = LocalKoda.current
    Text("…", fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.faint,
        modifier = Modifier.padding(start = (8 + depth * 14).dp, top = 3.dp, bottom = 3.dp))
}
