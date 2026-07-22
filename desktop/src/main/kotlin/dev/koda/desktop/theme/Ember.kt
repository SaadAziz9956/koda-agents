package dev.koda.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Ember — Koda's identity, applied as a skin over the Material 3 Expressive
 * engine. Warm graphite neutrals with a single vermilion accent used only as
 * the "live/active" signal; semantic colors are kept out of the accent so
 * state never fights brand. Both themes are first-class.
 */
object Ember {
    // dark
    val dGround = Color(0xFF141313); val dSurface = Color(0xFF1C1B1A); val dSurface2 = Color(0xFF252322)
    val dBorder = Color(0xFF322F2D); val dBorderStrong = Color(0xFF443F3C)
    val dText = Color(0xFFEAE6E2); val dTextDim = Color(0xFFA0968E)
    val dAccent = Color(0xFFF0663C); val dOnAccent = Color(0xFF1B0B06)
    // light
    val lGround = Color(0xFFF2F0EE); val lSurface = Color(0xFFFBFAF9); val lSurface2 = Color(0xFFE9E5E2)
    val lBorder = Color(0xFFDAD4D0); val lBorderStrong = Color(0xFFC7BFB9)
    val lText = Color(0xFF241F1C); val lTextDim = Color(0xFF665D57)
    val lAccent = Color(0xFFD24A22); val lOnAccent = Color(0xFFFFFFFF)
    // semantic (shared intent, tuned per theme where needed)
    val ok = Color(0xFF84B369); val danger = Color(0xFFE0796A); val info = Color(0xFF7BA6D6)

    val mono: FontFamily = FontFamily.Monospace
}

private val emberDark: ColorScheme = darkColorScheme(
    primary = Ember.dAccent, onPrimary = Ember.dOnAccent,
    secondary = Ember.dAccent, onSecondary = Ember.dOnAccent,
    background = Ember.dGround, onBackground = Ember.dText,
    surface = Ember.dSurface, onSurface = Ember.dText,
    surfaceVariant = Ember.dSurface2, onSurfaceVariant = Ember.dTextDim,
    outline = Ember.dBorderStrong, outlineVariant = Ember.dBorder,
    error = Ember.danger,
)

private val emberLight: ColorScheme = lightColorScheme(
    primary = Ember.lAccent, onPrimary = Ember.lOnAccent,
    secondary = Ember.lAccent, onSecondary = Ember.lOnAccent,
    background = Ember.lGround, onBackground = Ember.lText,
    surface = Ember.lSurface, onSurface = Ember.lText,
    surfaceVariant = Ember.lSurface2, onSurfaceVariant = Ember.lTextDim,
    outline = Ember.lBorderStrong, outlineVariant = Ember.lBorder,
    error = Ember.danger,
)

/** Tightened shape scale — a pro instrument, not M3's default pills. */
private val kodaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(11.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

/**
 * Ember on Material 3. We run on the stable [MaterialTheme] because
 * `MaterialExpressiveTheme` is still an internal API in this Compose
 * Multiplatform build; the expressive motion scheme layers in unchanged once
 * that API is public (the color/shape identity here is already the target).
 */
@Composable
fun KodaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) emberDark else emberLight,
        shapes = kodaShapes,
        content = content,
    )
}
