package dev.koda.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Token-usage donut. Null percent renders just the track. */
@Composable
fun ContextRing(percent: Int?, size: Dp = 22.dp) {
    val track = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(size)) {
        val stroke = 3.dp.toPx()
        val d = this.size.minDimension - stroke
        val tl = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f)
        drawArc(track, -90f, 360f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        if (percent != null && percent > 0) {
            drawArc(accent, -90f, percent / 100f * 360f, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun HDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
fun VDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A blinking block caret for the streaming line. */
@Composable
fun blinkAlpha(): Float {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
    )
    return a
}

/** Rounded chip used for mode/hints. */
val ChipShape = RoundedCornerShape(7.dp)

/** A subtle moving highlight sweep — signals "working" on live/streaming UI. */
fun Modifier.shimmer(): Modifier = composed {
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val t = rememberInfiniteTransition()
    val x by t.animateFloat(
        initialValue = -0.3f, targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
    )
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                startX = (x - 0.2f) * w, endX = (x + 0.2f) * w,
            ),
        )
    }
}
