package dev.koda.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.koda.desktop.theme.Ember

/**
 * A compact Markdown renderer for assistant messages — a practical subset:
 * headings, fenced code blocks, bullet/numbered lists, and inline **bold** /
 * `code`. Parsing is plain Kotlin; rendering is a column of styled Text.
 */
private sealed interface Md {
    data class Heading(val level: Int, val text: String) : Md
    data class Code(val text: String) : Md
    data class Bullet(val text: String) : Md
    data class Para(val text: String) : Md
}

private fun parse(src: String): List<Md> {
    val out = ArrayList<Md>()
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trimStart()
        if (t.startsWith("```")) {
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body.appendLine(lines[i]); i++ }
            i++ // consume closing fence
            out.add(Md.Code(body.toString().trimEnd('\n')))
            continue
        }
        when {
            t.startsWith("### ") -> out.add(Md.Heading(3, t.removePrefix("### ")))
            t.startsWith("## ") -> out.add(Md.Heading(2, t.removePrefix("## ")))
            t.startsWith("# ") -> out.add(Md.Heading(1, t.removePrefix("# ")))
            t.startsWith("- ") || t.startsWith("* ") -> out.add(Md.Bullet(t.drop(2)))
            t.matchesOrdered() -> out.add(Md.Bullet(t.substringAfter(". ")))
            t.isBlank() -> {}
            else -> out.add(Md.Para(raw))
        }
        i++
    }
    return out
}

private fun String.matchesOrdered(): Boolean {
    val dot = indexOf(". ")
    return dot in 1..3 && substring(0, dot).all { it.isDigit() }
}

@Composable
fun MarkdownText(markdown: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (block in parse(markdown)) {
            when (block) {
                is Md.Heading -> Text(
                    block.text,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 17.sp; 2 -> 15.sp; else -> 14.sp },
                )
                is Md.Code -> CodeBlock(block.text)
                is Md.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                    Text(inline(block.text), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                }
                is Md.Para -> Text(inline(block.text), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()).padding(11.dp),
    ) {
        Text(code, fontFamily = Ember.mono, fontSize = 12.5f.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Inline **bold** and `code`. */
@Composable
private fun inline(s: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) -> {
                    val end = s.indexOf("**", i + 2)
                    if (end < 0) { append(s.substring(i)); i = s.length } else {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                        i = end + 2
                    }
                }
                s[i] == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end < 0) { append(s.substring(i)); i = s.length } else {
                        withStyle(SpanStyle(color = codeColor, fontFamily = Ember.mono)) { append(s.substring(i + 1, end)) }
                        i = end + 1
                    }
                }
                else -> { append(s[i]); i++ }
            }
        }
    }
}
