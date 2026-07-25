package dev.koda.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.EvaIcons
import compose.icons.FeatherIcons
import compose.icons.evaicons.Outline
import compose.icons.evaicons.outline.ArrowForward
import compose.icons.evaicons.outline.Attach2
import compose.icons.evaicons.outline.ChevronRight
import compose.icons.evaicons.outline.Clock
import compose.icons.evaicons.outline.Folder
import compose.icons.evaicons.outline.Home
import compose.icons.evaicons.outline.Moon
import compose.icons.evaicons.outline.Search
import compose.icons.evaicons.outline.Settings2
import compose.icons.evaicons.outline.Shield
import compose.icons.evaicons.outline.Sun
import compose.icons.feathericons.GitBranch

/**
 * Koda's icon set — Eva Icons (open-source, outline) behind the [KIcons] facade
 * so call sites (`KIcons.moon`) stay unchanged. Feather supplies the one glyph
 * Eva doesn't have (git-branch). Tint via `Icon(icon, tint = ...)`.
 */
object KIcons {
    val moon: ImageVector = EvaIcons.Outline.Moon
    val sun: ImageVector = EvaIcons.Outline.Sun
    val search: ImageVector = EvaIcons.Outline.Search
    val gitBranch: ImageVector = FeatherIcons.GitBranch
    val folder: ImageVector = EvaIcons.Outline.Folder
    val shield: ImageVector = EvaIcons.Outline.Shield
    val clock: ImageVector = EvaIcons.Outline.Clock
    val arrowRight: ImageVector = EvaIcons.Outline.ArrowForward
    val home: ImageVector = EvaIcons.Outline.Home
    val chevronRight: ImageVector = EvaIcons.Outline.ChevronRight
    val attach: ImageVector = EvaIcons.Outline.Attach2
    val gear: ImageVector = EvaIcons.Outline.Settings2
}
