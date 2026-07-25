package dev.koda.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.AppModel
import dev.koda.desktop.theme.JetBrainsMono
import dev.koda.desktop.theme.KodaColors
import dev.koda.desktop.theme.LocalKoda
import androidx.compose.material3.Text

/**
 * The Code workspace — a syntax-highlighted, line-numbered file view with a tab
 * strip and breadcrumb, ported from the design handoff's editor pane. The
 * desktop is a remote client, so file bytes arrive over the protocol
 * (GetFile → FileContent); this renders whatever is currently open.
 */

private val KEYWORDS = setOf(
    "const", "let", "return", "function", "export", "if", "import", "from", "await",
    "async", "new", "for", "of", "true", "false", "null", "interface", "string",
    "number", "boolean", "void", "class", "extends", "implements", "public", "private",
    "val", "var", "fun", "when", "else", "is", "in", "object", "data", "override", "suspend",
)

// Strings | keywords | numbers | function-call identifiers — a light TS/JS/Kotlin
// tokenizer mirroring the handoff's hlTokens regex.
private val HL = Regex(
    """("[^"]*"|'[^']*'|`[^`]*`)|\b([A-Za-z_]\w*)\b|\b(\d[\d._]*)\b|\b([A-Za-z_]\w*)(?=\()""",
)

/** New-file line numbers that a unified diff adds — used for change markers. */
private fun changedLines(diff: String): Set<Int> {
    val out = HashSet<Int>()
    var n = 0
    for (line in diff.lines()) {
        when {
            line.startsWith("@@") -> Regex("""\+([0-9]+)""").find(line)?.let { n = it.groupValues[1].toInt() }
            line.startsWith("+++") -> {}
            line.startsWith("+") -> out.add(n++)
            line.startsWith("-") -> {}
            else -> n++
        }
    }
    return out
}

/** Highlight a single source line into a colored [AnnotatedString]. */
private fun highlight(line: String, k: KodaColors): AnnotatedString = buildAnnotatedString {
    // A // comment runs to end of line; color the tail and stop.
    val cm = line.indexOf("//")
    val code = if (cm >= 0) line.substring(0, cm) else line
    val rest = if (cm >= 0) line.substring(cm) else ""
    var last = 0
    for (m in HL.findAll(code)) {
        if (m.range.first > last) append(code.substring(last, m.range.first))
        val g = m.groups
        when {
            g[1] != null -> withStyle(SpanStyle(color = k.synString)) { append(m.value) }
            g[3] != null -> withStyle(SpanStyle(color = k.synNumber)) { append(m.value) }
            g[4] != null -> withStyle(SpanStyle(color = k.synFn)) { append(m.value) }
            g[2] != null && m.value in KEYWORDS -> withStyle(SpanStyle(color = k.synKeyword)) { append(m.value) }
            else -> append(m.value)
        }
        last = m.range.last + 1
    }
    if (last < code.length) append(code.substring(last))
    if (rest.isNotEmpty()) withStyle(SpanStyle(color = k.synComment)) { append(rest) }
}

@Composable
fun CodeEditor(model: AppModel, modifier: Modifier = Modifier) {
    val k = LocalKoda.current
    val path = model.openFilePath
    Column(modifier.fillMaxSize().background(k.surface)) {
        // ── Tab strip ──
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(k.bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path != null) {
                Row(
                    Modifier.height(36.dp).background(k.surface).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(k.info))
                    Spacer(Modifier.width(8.dp))
                    Text(path.substringAfterLast('/'), fontFamily = JetBrainsMono, fontSize = 12.sp, color = k.text)
                    if (model.openFileError != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("!", fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.danger)
                    }
                }
                VDivider()
            }
            Spacer(Modifier.weight(1f))
            if (path != null) {
                Text(
                    path.split('/').joinToString("  /  "),
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.faint,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
        HDivider()
        // ── Editor body ── opening a file fades+lifts in (shared-element feel).
        Crossfade(targetState = path, animationSpec = tween(240), label = "fileOpen", modifier = Modifier.fillMaxSize()) { p ->
            if (p == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Open a file from the Files panel →", fontSize = 13.sp, color = k.faint)
                }
            } else if (model.openFileError != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(model.openFileError ?: "", fontSize = 13.sp, color = k.danger, fontFamily = JetBrainsMono)
                }
            } else {
                val lines = model.openFileContent.split('\n')
                val changed = remember(model.gitDiff, model.gitDiffPath, p) {
                    if (model.gitDiffPath == p && model.gitDiff.isNotBlank()) changedLines(model.gitDiff) else emptySet()
                }
                val h = rememberScrollState()
                Column(Modifier.fillMaxSize().enterUp(riseDp = 4f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Column(Modifier.horizontalScroll(h)) {
                        lines.forEachIndexed { i, line ->
                            val isChanged = changed.contains(i + 1)
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                                    .background(if (isChanged) k.diffAddBg else androidx.compose.ui.graphics.Color.Transparent),
                            ) {
                                Text(
                                    "${i + 1}", fontFamily = JetBrainsMono, fontSize = 12.5f.sp, color = k.faint,
                                    textAlign = TextAlign.End, modifier = Modifier.width(44.dp).padding(end = 16.dp),
                                )
                                Text(
                                    if (isChanged) "▸" else "", fontFamily = JetBrainsMono, fontSize = 11.sp, color = k.accent,
                                    textAlign = TextAlign.Center, modifier = Modifier.width(14.dp),
                                )
                                Text(
                                    highlight(line, k), fontFamily = JetBrainsMono, fontSize = 12.5f.sp,
                                    color = k.text, softWrap = false, lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
