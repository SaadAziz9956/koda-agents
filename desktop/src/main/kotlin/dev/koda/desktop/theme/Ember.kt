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
    val ok = Color(0xFF4E8A4E)
    val danger = Color(0xFFB0463F)
    val info = Color(0xFF3E7BB0)
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

// Minimalist Utilitarian — warm monochrome canvas, charcoal ink, a single
// near-black primary action, muted pastel semantics, no gradients. Light is the
// primary surface; the dark variant inverts coherently (light ink on charcoal).

val EmberLight = KodaColors(
    bg = Color(0xFFF7F6F3), rail = Color(0xFFFBFBFA), surface = Color(0xFFFFFFFF), raised = Color(0xFFF9F9F8),
    border = Color(0xFFEAEAEA), strong = Color(0xFFE2E1DD),
    text = Color(0xFF2F3437), dim = Color(0xFF787774), faint = Color(0xFF9B9A97),
    // Primary action is near-black (not a bright accent); on-accent is white.
    accent = Color(0xFF1A1A19), accentSoft = Color(0xFF333331), onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF346538), danger = Color(0xFF9F2F2D), info = Color(0xFF1F6C9F),
    synKeyword = Color(0xFF1F6C9F), synString = Color(0xFF346538), synNumber = Color(0xFF956400),
    synComment = Color(0xFF9B9A97), synFn = Color(0xFF2F3437),
    diffAddBg = Color(0xFFEDF3EC), diffAddTx = Color(0xFF346538),
    diffDelBg = Color(0xFFFDEBEC), diffDelTx = Color(0xFF9F2F2D), hunkBg = Color(0xFFE1F3FE),
    sel = Color(0x0F1A1A19), glow = Color(0x141A1A19), hi = Color(0x05000000),
    isDark = false,
)

val EmberDark = KodaColors(
    bg = Color(0xFF191817), rail = Color(0xFF1E1D1B), surface = Color(0xFF242220), raised = Color(0xFF2B2926),
    border = Color(0xFF34322F), strong = Color(0xFF46443F),
    text = Color(0xFFECEAE5), dim = Color(0xFFA4A099), faint = Color(0xFF6E6A63),
    // Inverted: primary action is near-white on dark, with dark ink on it.
    accent = Color(0xFFECEAE5), accentSoft = Color(0xFFC9C6C0), onAccent = Color(0xFF191817),
    ok = Color(0xFF82B082), danger = Color(0xFFD98C86), info = Color(0xFF7FA8D6),
    synKeyword = Color(0xFF7FA8D6), synString = Color(0xFF8FBF8F), synNumber = Color(0xFFD9B47F),
    synComment = Color(0xFF6E6A63), synFn = Color(0xFFECEAE5),
    diffAddBg = Color(0x2482B082), diffAddTx = Color(0xFFA6D0A6),
    diffDelBg = Color(0x24D98C86), diffDelTx = Color(0xFFE6ADA8), hunkBg = Color(0x1F7FA8D6),
    sel = Color(0x14FFFFFF), glow = Color(0x1FFFFFFF), hi = Color(0x0BFFFFFF),
    isDark = true,
)

val LocalKoda = staticCompositionLocalOf { EmberLight }

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

// Type scale is 1:1 with the Ember design system's Figma variables:
// Display 26/600 (−0.52), Heading 18/600 (−0.48), Body 14.5/400, Caption 12,
// Eyebrow 11/600 uppercase (+0.66). Geist for UI, JetBrains Mono for machine.
private val kodaTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Geist, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.52).sp, lineHeight = 30.sp),
        titleLarge = titleLarge.copy(fontFamily = Geist, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.48).sp),
        titleMedium = titleMedium.copy(fontFamily = Geist, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Geist, fontSize = 14.5.sp, lineHeight = 23.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Geist, fontSize = 14.sp),
        bodySmall = bodySmall.copy(fontFamily = Geist, fontSize = 12.sp),
        labelLarge = labelLarge.copy(fontFamily = Geist, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = Geist, fontSize = 11.sp, letterSpacing = 0.66.sp, fontWeight = FontWeight.SemiBold),
    )
}

// Radii from the Figma variables: corner radius 5 / 8 / 13.
private val kodaShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(13.dp),
    large = RoundedCornerShape(13.dp),
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
