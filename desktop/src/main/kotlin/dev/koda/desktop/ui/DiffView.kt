package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.LocalKoda

/**
 * A parsed unified-diff row carrying the resolved old/new line numbers so the
 * gutter can show both sides, exactly like the design handoff's diff table.
 */
private data class DiffRow(val old: Int?, val new: Int?, val kind: Char, val text: String)

private fun parseDiff(diff: String): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    var oldNo = 0
    var newNo = 0
    for (line in diff.lines()) {
        when {
            line.startsWith("+++") || line.startsWith("---") -> {
                rows += DiffRow(null, null, 'F', line)
            }
            line.startsWith("@@") -> {
                // @@ -a,b +c,d @@ — seed the counters from the hunk header.
                val m = Regex("""-([0-9]+)(?:,[0-9]+)?\s+\+([0-9]+)""").find(line)
                if (m != null) {
                    oldNo = m.groupValues[1].toInt()
                    newNo = m.groupValues[2].toInt()
                }
                rows += DiffRow(null, null, '@', line)
            }
            line.startsWith("+") -> rows += DiffRow(null, newNo++, '+', line.drop(1))
            line.startsWith("-") -> rows += DiffRow(oldNo++, null, '-', line.drop(1))
            else -> {
                val t = if (line.startsWith(" ")) line.drop(1) else line
                rows += DiffRow(oldNo++, newNo++, ' ', t)
            }
        }
    }
    return rows
}

/**
 * Renders a unified diff as a two-gutter table — old line number, new line
 * number, then the code — with additions/deletions/hunk headers tinted from the
 * Ember diff tokens. Used for agent edits and git diffs alike.
 */
@Composable
fun DiffView(diff: String, maxHeight: Int = 320, embedded: Boolean = false) {
    val k = LocalKoda.current
    val rows = parseDiff(diff)
    val shape = RoundedCornerShape(9.dp)
    val base = Modifier.fillMaxWidth()
        .then(if (embedded) Modifier else Modifier.clip(shape).background(k.bg))
        .heightIn(max = maxHeight.dp)
        .verticalScroll(rememberScrollState())
    Column(base) {
        val h = rememberScrollState()
        Column(Modifier.horizontalScroll(h).padding(vertical = 6.dp)) {
            for (r in rows) {
                val (bg, fg) = when (r.kind) {
                    '+' -> k.diffAddBg to k.diffAddTx
                    '-' -> k.diffDelBg to k.diffDelTx
                    '@' -> k.hunkBg to k.info
                    'F' -> Color.Transparent to k.faint
                    else -> Color.Transparent to k.dim
                }
                Row(
                    Modifier.fillMaxWidth().background(bg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Gutter(r.old)
                    Gutter(r.new)
                    val sign = when (r.kind) { '+' -> "+"; '-' -> "−"; else -> " " }
                    Text(
                        sign,
                        color = fg,
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        modifier = Modifier.width(14.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (r.text.isEmpty()) " " else r.text,
                        color = fg,
                        fontFamily = JetBrainsMono,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Gutter(n: Int?) {
    val k = LocalKoda.current
    Box(Modifier.width(38.dp).padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
        Text(
            n?.toString() ?: "",
            color = k.faint,
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
        )
    }
}
