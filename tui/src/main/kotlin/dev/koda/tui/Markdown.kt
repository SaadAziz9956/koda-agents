package dev.koda.tui

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * A compact Markdown renderer for Mosaic — Mosaic ships no markdown support,
 * so we parse a practical subset to styled [Text] lines: headings, bullet and
 * numbered lists, fenced code blocks, and inline **bold** / `code`.
 */
@Composable
fun MarkdownText(markdown: String) {
    Column {
        var inFence = false
        for (raw in markdown.lines()) {
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                continue
            }
            when {
                inFence ->
                    Text(raw, color = KodaColors.code, textStyle = TextStyle.Dim)

                trimmed.startsWith("### ") ->
                    Text(trimmed.removePrefix("### "), color = KodaColors.heading, textStyle = TextStyle.Bold)

                trimmed.startsWith("## ") ->
                    Text(trimmed.removePrefix("## "), color = KodaColors.heading, textStyle = TextStyle.Bold)

                trimmed.startsWith("# ") ->
                    Text(trimmed.removePrefix("# "), color = KodaColors.heading, textStyle = TextStyle.Bold)

                trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                    Text(inline("  • " + trimmed.drop(2)))

                trimmed.matchesOrderedList() ->
                    Text(inline("  " + trimmed))

                else ->
                    Text(inline(raw))
            }
        }
    }
}

private fun String.matchesOrderedList(): Boolean {
    val dot = indexOf(". ")
    return dot in 1..3 && substring(0, dot).all { it.isDigit() }
}

/** Parses inline **bold** and `code` into a styled annotated string. */
private fun inline(s: String) = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { append(s.substring(i)); i = s.length } else {
                    pushStyle(SpanStyle(textStyle = TextStyle.Bold))
                    append(s.substring(i + 2, end)); pop(); i = end + 2
                }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { append(s.substring(i)); i = s.length } else {
                    pushStyle(SpanStyle(color = KodaColors.code))
                    append(s.substring(i + 1, end)); pop(); i = end + 1
                }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

object KodaColors {
    private val gray = Color(0.5f, 0.5f, 0.5f)
    val heading = Color.Cyan
    val code = Color.Green
    val tool = gray
    val ok = Color.Green
    val err = Color.Red
    val warn = Color.Yellow
    val dim = gray
    val accent = Color.Cyan
    val border = Color(0.45f, 0.45f, 0.55f)
}
