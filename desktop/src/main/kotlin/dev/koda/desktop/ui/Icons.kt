package dev.koda.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Folder
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Home
import compose.icons.feathericons.Moon
import compose.icons.feathericons.Paperclip
import compose.icons.feathericons.Search
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sun

/**
 * Koda's icon set — Feather (open-source, thin line, 24×24 stroke). Kept behind
 * the [KIcons] facade so call sites (`KIcons.moon`) stay unchanged while the
 * glyphs come from a real, consistent, well-drawn library instead of hand-ported
 * paths. Tint via `Icon(icon, tint = ...)`.
 */
object KIcons {
    val moon: ImageVector = FeatherIcons.Moon
    val sun: ImageVector = FeatherIcons.Sun
    val search: ImageVector = FeatherIcons.Search
    val gitBranch: ImageVector = FeatherIcons.GitBranch
    val folder: ImageVector = FeatherIcons.Folder
    val shield: ImageVector = FeatherIcons.Shield
    val clock: ImageVector = FeatherIcons.Clock
    val arrowRight: ImageVector = FeatherIcons.ArrowRight
    val home: ImageVector = FeatherIcons.Home
    val chevronRight: ImageVector = FeatherIcons.ChevronRight
    val attach: ImageVector = FeatherIcons.Paperclip
    val gear: ImageVector = FeatherIcons.Settings
}
