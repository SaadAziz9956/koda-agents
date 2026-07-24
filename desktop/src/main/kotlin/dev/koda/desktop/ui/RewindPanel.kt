package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.LocalKoda
import dev.koda.protocol.CheckpointInfo

/**
 * The Rewind tab — a per-turn checkpoint timeline. Each turn is a restore point
 * for both the conversation and the files it touched; the most recent is marked
 * NOW, and a footer shows what a rewind would revert.
 */
@Composable
fun RewindPanel(model: AppModel, modifier: Modifier = Modifier) {
    val k = LocalKoda.current
    Column(modifier.background(k.rail).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            "Per-turn checkpoints. Rewind restores files & conversation to that point.",
            fontSize = 12.sp, color = k.dim, lineHeight = 17.sp,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        if (model.checkpoints.isEmpty()) {
            Text("No checkpoints yet.", fontSize = 12.sp, color = k.faint, fontFamily = JetBrainsMono)
            return@Column
        }
        model.checkpoints.forEachIndexed { i, cp ->
            CheckpointRow(cp, isFirst = i == 0, isLast = i == model.checkpoints.lastIndex) { model.rewind(cp.index) }
        }
        val target = model.checkpoints.firstOrNull { it.index >= 1 }
        if (target != null) {
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(9.dp))
                    .background(k.surface).border(1.dp, k.border, RoundedCornerShape(9.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Text("Rewinding to “${target.label.ifBlank { "turn ${target.index}" }}” reverts:", fontSize = 11.sp, color = k.faint, modifier = Modifier.padding(bottom = 6.dp))
                Text("↺ ${target.fileCount} file(s) restored to that point", fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.diffDelTx)
            }
        }
    }
}

@Composable
private fun CheckpointRow(cp: CheckpointInfo, isFirst: Boolean, isLast: Boolean, onRewind: () -> Unit) {
    val k = LocalKoda.current
    val now = cp.index == 1 || isFirst
    val dotColor = when {
        now -> k.accent
        cp.fileCount > 0 -> k.ok
        else -> k.faint
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Rail + dot column.
        Box(Modifier.width(22.dp).fillMaxHeight()) {
            Box(
                Modifier.width(1.5.dp).fillMaxHeight()
                    .padding(top = if (isFirst) 6.dp else 0.dp, bottom = if (isLast) 6.dp else 0.dp)
                    .background(k.border).align(Alignment.TopCenter),
            )
            Box(
                Modifier.padding(top = 5.dp).size(17.dp).align(Alignment.TopCenter),
                contentAlignment = Alignment.Center,
            ) {
                if (now) Box(Modifier.size(17.dp).clip(RoundedCornerShape(50)).background(k.sel))
                Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(dotColor).border(2.dp, k.rail, RoundedCornerShape(50)))
            }
        }
        // Card.
        Column(
            Modifier.weight(1f).padding(bottom = 16.dp).clip(RoundedCornerShape(9.dp))
                .then(if (now) Modifier.background(k.surface).border(1.dp, k.strong, RoundedCornerShape(9.dp)) else Modifier.border(1.dp, k.border, RoundedCornerShape(9.dp)))
                .padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("turn ${cp.index}", fontFamily = JetBrainsMono, fontSize = 10.sp, color = k.faint)
                if (now) {
                    Box(Modifier.clip(RoundedCornerShape(5.dp)).border(1.dp, k.accent, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp)) {
                        Text("NOW", fontFamily = JetBrainsMono, fontSize = 9.sp, color = k.accent)
                    }
                }
            }
            Text(cp.label.ifBlank { "(turn ${cp.index})" }, fontSize = 12.5f.sp, color = k.text, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${cp.fileCount} file(s)", fontFamily = JetBrainsMono, fontSize = 10.5f.sp, color = k.dim)
                if (cp.index >= 1) {
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, k.border, RoundedCornerShape(6.dp))
                            .clickable(onClick = onRewind).padding(horizontal = 8.dp, vertical = 1.dp),
                    ) { Text("↺ Rewind", fontFamily = JetBrainsMono, fontSize = 10.5f.sp, color = k.accent) }
                }
            }
        }
    }
}
