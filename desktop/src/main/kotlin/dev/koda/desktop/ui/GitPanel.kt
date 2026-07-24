package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.LocalKoda
import dev.koda.protocol.GitFileChange

/**
 * The Git tab — branch/ahead-behind header, a change list, and a commit box
 * with Commit / Open PR, all reusing the shared DiffView for review.
 */
@Composable
fun GitPanel(model: AppModel, modifier: Modifier = Modifier) {
    val k = LocalKoda.current
    var message by remember { mutableStateOf("") }
    Column(modifier.background(k.rail).verticalScroll(rememberScrollState()).padding(14.dp)) {
        if (!model.gitOk) {
            Text("not a git repository", fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.dim)
            return@Column
        }
        // Branch card.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(k.surface).border(1.dp, k.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(KIcons.gitBranch, contentDescription = null, tint = k.accentSoft, modifier = Modifier.size(14.dp))
            Text(model.gitBranch.ifBlank { "HEAD" }, fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.text, modifier = Modifier.weight(1f), maxLines = 1)
            Text("↑${model.gitAhead}", fontFamily = JetBrainsMono, fontSize = 10.5f.sp, color = if (model.gitAhead > 0) k.ok else k.faint)
            Text("↓${model.gitBehind}", fontFamily = JetBrainsMono, fontSize = 10.5f.sp, color = k.faint)
            Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.refreshGit() }.padding(horizontal = 4.dp)) {
                Text("⟳", fontSize = 12.sp, color = k.dim)
            }
        }
        // Changes.
        Text(
            "Changes (${model.gitFiles.size})",
            fontSize = 10.5f.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = k.faint,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        if (model.gitFiles.isEmpty()) {
            Text("working tree clean", fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.dim)
        } else {
            model.gitFiles.forEach { f -> GitFileRow(f, model.gitDiffPath == f.path) { model.showGitDiff(f.path) } }
        }
        // Selected diff.
        if (model.gitDiff.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            DiffView(model.gitDiff, maxHeight = 260)
        }
        // Commit box.
        Spacer(Modifier.height(16.dp))
        EmberField(message, { message = it }, "Commit message…", Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EmberButton("Commit", onClick = { if (message.isNotBlank()) { model.gitCommit(message); message = "" } }, modifier = Modifier.weight(1f))
            EmberGhostButton("Open PR ↗", onClick = { model.createPr(model.gitBranch.ifBlank { "Koda changes" }) })
        }
    }
}

@Composable
private fun GitFileRow(f: GitFileChange, selected: Boolean, onClick: () -> Unit) {
    val k = LocalKoda.current
    val (glyph, color) = when (f.status) {
        "added" -> "A" to k.diffAddTx
        "deleted" -> "D" to k.diffDelTx
        "untracked" -> "?" to k.faint
        else -> "M" to k.diffAddTx
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp))
            .background(if (selected) k.surface else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(glyph, fontFamily = JetBrainsMono, fontSize = 11.sp, color = color, modifier = Modifier.width(12.dp))
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(k.info))
        Text(f.path.substringAfterLast('/'), fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.text, modifier = Modifier.weight(1f), maxLines = 1)
        if (f.staged) Text("staged", fontFamily = JetBrainsMono, fontSize = 9.5f.sp, color = k.ok)
    }
}
