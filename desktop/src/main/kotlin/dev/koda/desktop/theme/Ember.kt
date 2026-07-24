package dev.koda.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ember — Koda's "warm instrument, at dusk" design system, recreated from the
 * Claude-design handoff. Full token set lives on [KodaColors] (accessible via
 * [LocalKoda]); the Material [ColorScheme] is derived from it so existing
 * material components stay on-palette. Geist (UI) + JetBrains Mono (machine
 * output) are bundled in resources/font.
 */

// ── Fonts ───────────────────────────────────────────────────────────────────
val Geist = FontFamily(
    Font("font/Geist-Regular.ttf", FontWeight.Normal),
    Font("font/Geist-Medium.ttf", FontWeight.Medium),
    Font("font/Geist-SemiBold.ttf", FontWeight.SemiBold),
    Font("font/Geist-Bold.ttf", FontWeight.Bold),
)
val JetBrainsMono = FontFamily(
    Font("font/JetBrainsMono-Regular.ttf", FontWeight.Normal),
    Font("font/JetBrainsMono-Medium.ttf", FontWeight.Medium),
    Font("font/JetBrainsMono-SemiBold.ttf", FontWeight.SemiBold),
    Font("font/JetBrainsMono-Bold.ttf", FontWeight.Bold),
)

/** Compatibility shim for existing call sites. */
object Ember {
    val sans: FontFamily = Geist
    val mono: FontFamily = JetBrainsMono
    val ok = Color(0xFF84B369)
    val danger = Color(0xFFE0796A)
    val info = Color(0xFF7BA6D6)
}

// ── Token set ─────────────────────────────────────────────────────────────
data class KodaColors(
    val bg: Color, val rail: Color, val surface: Color, val raised: Color,
    val border: Color, val strong: Color,
    val text: Color, val dim: Color, val faint: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val ok: Color, val danger: Color, val info: Color,
    val synKeyword: Color, val synString: Color, val synNumber: Color, val synComment: Color, val synFn: Color,
    val diffAddBg: Color, val diffAddTx: Color, val diffDelBg: Color, val diffDelTx: Color, val hunkBg: Color,
    val sel: Color, val glow: Color, val hi: Color,
    val isDark: Boolean,
)

val EmberDark = KodaColors(
    bg = Color(0xFF141313), rail = Color(0xFF181716), surface = Color(0xFF1C1B1A), raised = Color(0xFF252322),
    border = Color(0xFF322F2D), strong = Color(0xFF443F3C),
    text = Color(0xFFEAE6E2), dim = Color(0xFFA0968E), faint = Color(0xFF69625C),
    accent = Color(0xFFF0663C), accentSoft = Color(0xFFF07E5A), onAccent = Color(0xFF1B0B06),
    ok = Color(0xFF84B369), danger = Color(0xFFE0796A), info = Color(0xFF7BA6D6),
    synKeyword = Color(0xFF7BA6D6), synString = Color(0xFF84B369), synNumber = Color(0xFFE0A05A),
    synComment = Color(0xFF69625C), synFn = Color(0xFFF07E5A),
    diffAddBg = Color(0x2184B369), diffAddTx = Color(0xFFAED392),
    diffDelBg = Color(0x21E0796A), diffDelTx = Color(0xFFE6A99F), hunkBg = Color(0x177BA6D6),
    sel = Color(0x24F0663C), glow = Color(0x59F0663C), hi = Color(0x0BFFFFFF),
    isDark = true,
)

val EmberLight = KodaColors(
    bg = Color(0xFFF2F0EE), rail = Color(0xFFEDEAE6), surface = Color(0xFFFBFAF9), raised = Color(0xFFE9E5E2),
    border = Color(0xFFDAD4D0), strong = Color(0xFFC7BFB9),
    text = Color(0xFF241F1C), dim = Color(0xFF665D57), faint = Color(0xFFA79E98),
    accent = Color(0xFFD24A22), accentSoft = Color(0xFFE0714A), onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF84B369), danger = Color(0xFFE0796A), info = Color(0xFF7BA6D6),
    synKeyword = Color(0xFF3F6A9A), synString = Color(0xFF4F7A34), synNumber = Color(0xFFA5702A),
    synComment = Color(0xFFA79E98), synFn = Color(0xFFC05A30),
    diffAddBg = Color(0x26609640), diffAddTx = Color(0xFF3F6A24),
    diffDelBg = Color(0x21C8503C), diffDelTx = Color(0xFFA13C2C), hunkBg = Color(0x1A5A82B4),
    sel = Color(0x1AD24A22), glow = Color(0x40D24A22), hi = Color(0xB3FFFFFF),
    isDark = false,
)

val LocalKoda = staticCompositionLocalOf { EmberDark }

private fun scheme(k: KodaColors): ColorScheme =
    (if (k.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = k.accent, onPrimary = k.onAccent, secondary = k.accent, onSecondary = k.onAccent,
        background = k.bg, onBackground = k.text,
        surface = k.surface, onSurface = k.text,
        surfaceVariant = k.raised, onSurfaceVariant = k.dim,
        surfaceContainerLow = k.rail,
        outline = k.strong, outlineVariant = k.border,
        error = k.danger,
    )

private val kodaTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontFamily = Geist, fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = Geist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Geist, fontSize = 14.5.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Geist, fontSize = 13.sp),
        labelLarge = labelLarge.copy(fontFamily = Geist, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = Geist, fontSize = 11.sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.Bold),
    )
}

private val kodaShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(11.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun KodaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val k = if (dark) EmberDark else EmberLight
    MaterialTheme(colorScheme = scheme(k), shapes = kodaShapes, typography = kodaTypography) {
        CompositionLocalProvider(
            LocalKoda provides k,
            // Default any un-styled Text to Geist + on-surface, so the whole app
            // picks up the real UI face without touching every call site.
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Geist, color = k.text),
            content = content,
        )
    }
}
