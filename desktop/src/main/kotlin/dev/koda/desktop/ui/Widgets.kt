package dev.koda.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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

/** Spinning ring (arc with a transparent gap at the head) — the tool "running"
 *  marker, matching the handoff's `koda-spin` border-top-transparent circle. */
@Composable
fun Spinner(size: Dp, color: Color, strokeWidth: Dp = 1.6.dp) {
    val t = rememberInfiniteTransition()
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(Modifier.size(size)) {
        val sw = strokeWidth.toPx()
        val d = this.size.minDimension - sw
        val tl = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f)
        // 300° sweep leaves a 60° gap, giving the "border-top-transparent" look.
        drawArc(color, angle, 300f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round))
    }
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

/**
 * Blink via the draw phase: the animated alpha is read inside `graphicsLayer`
 * (draw), so the node never recomposes — the Compose-perf "defer state reads
 * to the latest phase" rule, instead of reading blinkAlpha() in composition.
 */
fun Modifier.blink(periodMs: Int = 650): Modifier = composed {
    val t = rememberInfiniteTransition()
    val a by t.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(periodMs), RepeatMode.Reverse),
    )
    graphicsLayer { alpha = a }
}

/** koda-up: fade in while rising a few px — the entrance for stream cards.
 *  [delayMs] staggers list items (pass index * step). */
fun Modifier.enterUp(durationMs: Int = 280, riseDp: Float = 6f, delayMs: Int = 0): Modifier = composed {
    val anim = remember { Animatable(0f) }
    // Stagger via the spec's delayMillis (frame-clock driven) — robust and never
    // leaves an item stuck invisible if a dispatcher delay wouldn't fire.
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(durationMs, delayMillis = delayMs, easing = FastOutSlowInEasing)) }
    graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * riseDp.dp.toPx()
    }
}

/**
 * Press/hover spring scale + click, one shared interaction source. Scale is
 * applied in graphicsLayer (draw phase). Physics, not flash — a springy dip on
 * press and a slight lift on hover give instant, tactile feedback.
 */
fun Modifier.clickableScale(
    pressScale: Float = 0.97f,
    hoverScale: Float = 1.015f,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val target = if (pressed) pressScale else if (hovered) hoverScale else 1f
    val scale by animateFloatAsState(target, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), label = "pressScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .hoverable(interaction, enabled = enabled)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * Animated hover background tint for list rows — immediate feedback on every
 * hover. Keeps its own hover source; pair with the row's own clickable.
 */
fun Modifier.hoverBg(
    shape: androidx.compose.ui.graphics.Shape,
    hoverColor: Color,
    baseColor: Color = Color.Transparent,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) hoverColor else baseColor, tween(120), label = "hoverBg")
    hoverable(interaction).clip(shape).background(bg)
}

/** koda-fade: a plain opacity fade-in, for settled content like the summary. */
fun Modifier.enterFade(durationMs: Int = 350): Modifier = composed {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { anim.animateTo(1f, tween(durationMs, easing = LinearEasing)) }
    graphicsLayer { alpha = anim.value }
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
