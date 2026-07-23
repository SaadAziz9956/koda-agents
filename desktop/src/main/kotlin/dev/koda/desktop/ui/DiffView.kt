package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.theme.Ember

/**
 * Renders a unified diff with per-line coloring — additions, deletions, hunk
 * headers, context — in a scrollable mono box. Used for agent edits and git
 * diffs, so the same review surface serves both.
 */
@Composable
fun DiffView(diff: String, maxHeight: Int = 320) {
    val addBg = Color(0x2600C853); val delBg = Color(0x26E53935)
    val addFg = Ember.ok; val delFg = MaterialTheme.colorScheme.error
    val ctxFg = MaterialTheme.colorScheme.onSurfaceVariant
    val hunkFg = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .heightIn(max = maxHeight.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        val h = rememberScrollState()
        Column(Modifier.horizontalScroll(h).padding(vertical = 6.dp)) {
            for (line in diff.lines()) {
                val (fg, bg) = when {
                    line.startsWith("+++") || line.startsWith("---") -> ctxFg to Color.Transparent
                    line.startsWith("@@") -> hunkFg to Color.Transparent
                    line.startsWith("+") -> addFg to addBg
                    line.startsWith("-") -> delFg to delBg
                    else -> ctxFg to Color.Transparent
                }
                Text(
                    if (line.isEmpty()) " " else line,
                    color = fg,
                    fontFamily = Ember.mono,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp),
                )
            }
        }
    }
}
