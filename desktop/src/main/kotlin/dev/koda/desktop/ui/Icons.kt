package dev.koda.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Koda's line-icon set, ported 1:1 from the design handoff's inline SVGs
 * (24×24 viewBox, stroke=currentColor, round caps/joins). Rendered as tintable
 * [ImageVector]s — no emoji. Tint via `Icon(icon, tint = ...)`.
 */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun icon(vararg d: String, sw: Float = 1.7f): ImageVector {
    val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    d.forEach { path ->
        b.addPath(
            PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.White),
            strokeLineWidth = sw,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object KIcons {
    val moon = icon("M21 12.8A8.5 8.5 0 1 1 11.2 3a6.6 6.6 0 0 0 9.8 9.8z")
    val sun = icon(circle(12f, 12f, 3f), "M12 2.5v3M12 18.5v3M21.5 12h-3M5.5 12h-3M18.4 5.6l-2.1 2.1M7.7 16.3l-2.1 2.1M18.4 18.4l-2.1-2.1M7.7 7.7 5.6 5.6")
    val search = icon(circle(11f, 11f, 7f), "m20 20-3.5-3.5")
    val gitBranch = icon(circle(6f, 6f, 2.5f), circle(6f, 18f, 2.5f), circle(18f, 9f, 2.5f), "M6 8.5v7M18 11.5c0 3-4 3-4 6")
    val folder = icon("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val shield = icon("M12 2 3 6v6c0 5 3.8 8.4 9 10 5.2-1.6 9-5 9-10V6z")
    val clock = icon(circle(12f, 12f, 9f), "M12 8v5l3 2")
    val arrowRight = icon("m5 12 14 0M13 6l6 6-6 6")
    val home = icon("M3 12 12 4l9 8", "M6 10v9h12v-9")
    val chevronRight = icon("m9 6 6 6-6 6")
    val attach = icon("M21 12.5 12.5 21a5 5 0 0 1-7-7l8-8a3.3 3.3 0 0 1 4.7 4.7l-8 8a1.6 1.6 0 0 1-2.3-2.3l7.3-7.3")
    val gear = icon(circle(12f, 12f, 3f), "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-2.9 1.17V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 8 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 3.6 14H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 8.7l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 10 3.6V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 2.72 1.51l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 20.4 10H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z")
}
