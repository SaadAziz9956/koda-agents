package dev.koda.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Bold
import com.adamglin.phosphoricons.bold.ArrowRight
import com.adamglin.phosphoricons.bold.CaretRight
import com.adamglin.phosphoricons.bold.Clock
import com.adamglin.phosphoricons.bold.Folder
import com.adamglin.phosphoricons.bold.GitBranch
import com.adamglin.phosphoricons.bold.House
import com.adamglin.phosphoricons.bold.MagnifyingGlass
import com.adamglin.phosphoricons.bold.Moon
import com.adamglin.phosphoricons.bold.Paperclip
import com.adamglin.phosphoricons.bold.Gear
import com.adamglin.phosphoricons.bold.Shield
import com.adamglin.phosphoricons.bold.Sun

/**
 * Koda's icon set — Phosphor (Bold weight) behind the [KIcons] facade, per the
 * minimalist-editorial system's technical, thicker-stroke aesthetic. Call sites
 * (`KIcons.moon`) stay unchanged. Tint via `Icon(icon, tint = ...)`.
 */
object KIcons {
    val moon: ImageVector = PhosphorIcons.Bold.Moon
    val sun: ImageVector = PhosphorIcons.Bold.Sun
    val search: ImageVector = PhosphorIcons.Bold.MagnifyingGlass
    val gitBranch: ImageVector = PhosphorIcons.Bold.GitBranch
    val folder: ImageVector = PhosphorIcons.Bold.Folder
    val shield: ImageVector = PhosphorIcons.Bold.Shield
    val clock: ImageVector = PhosphorIcons.Bold.Clock
    val arrowRight: ImageVector = PhosphorIcons.Bold.ArrowRight
    val home: ImageVector = PhosphorIcons.Bold.House
    val chevronRight: ImageVector = PhosphorIcons.Bold.CaretRight
    val attach: ImageVector = PhosphorIcons.Bold.Paperclip
    val gear: ImageVector = PhosphorIcons.Bold.Gear
}
