package dev.koda.desktop.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.koda.desktop.AppModel
import dev.koda.desktop.DaemonClient
import dev.koda.desktop.ui.AppShell
import dev.koda.desktop.ui.AppView
import dev.koda.desktop.theme.KodaTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Offscreen renderer — draws the real app screens to PNGs with sample data so
 * the UI can be compared to the design handoff without a live daemon or a
 * visible window. Run via `./gradlew :desktop:renderScreens`. Not shipped.
 */
fun main(args: Array<String>) {
    val outDir = File(args.getOrNull(0) ?: "build/screens").apply { mkdirs() }
    val scope = CoroutineScope(Dispatchers.Default)

    fun render(name: String, w: Int, h: Int, dark: Boolean, density: Float = 1f, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = (w * density).toInt(), height = (h * density).toInt(), density = Density(density), coroutineContext = Dispatchers.Unconfined) {
            KodaTheme(dark = dark) { Box(Modifier.fillMaxSize()) { content() } }
        }
        try {
            // Step a few frames so one-shot entrance animations settle.
            var t = 0L
            repeat(40) { scene.render(t); t += 16_000_000L }
            val img = scene.render(t)
            val data = img.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
            File(outDir, "$name.png").writeBytes(data.bytes)
            println("wrote ${File(outDir, "$name.png").absolutePath}  ${img.width}x${img.height}")
        } finally {
            scene.close()
        }
    }

    val model = AppModel(DaemonClient(scope), scope).apply { previewSeed(0L) }

    // Match the reference screens' logical size (924×540 dp).
    render("home", 1280, 812, dark = false) {
        AppShell(model, view = AppView.Home, setView = {}, onOpenPalette = {}, dark = false, onToggleTheme = {})
    }
    render("code", 1280, 812, dark = false) {
        AppShell(model, view = AppView.Code, setView = {}, onOpenPalette = {}, dark = false, onToggleTheme = {})
    }
    render("connect", 900, 700, dark = false) {
        dev.koda.desktop.ui.ConnectScreen(model)
    }
    render("home-light", 924, 540, dark = false) {
        AppShell(model, view = AppView.Home, setView = {}, onOpenPalette = {}, dark = false, onToggleTheme = {})
    }
    // ⌘K palette over Home (light), matching the Figma frame.
    render("palette", 900, 620, dark = false) {
        Box(Modifier.fillMaxSize()) {
            AppShell(model, view = AppView.Home, setView = {}, onOpenPalette = {}, dark = false, onToggleTheme = {})
            dev.koda.desktop.ui.CommandPalette(model, onClose = {}, onNavigate = {})
        }
    }
    // Settings — Appearance, light, matching the Figma frame (1000×680).
    render("settings-appearance", 1000, 680, dark = false) {
        dev.koda.desktop.ui.SettingsScreen(model, dark = false, onToggleTheme = {}, onClose = {}, initialTab = "appearance")
    }
    // Right panels (344 wide), light, matching the Figma frames.
    render("panel-rewind", 344, 620, dark = false) { dev.koda.desktop.ui.RewindPanel(model, Modifier.fillMaxSize()) }
    render("panel-files", 344, 620, dark = false) { dev.koda.desktop.ui.FileExplorer(model) }
    render("panel-git", 344, 620, dark = false) { dev.koda.desktop.ui.GitPanel(model, Modifier.fillMaxSize()) }
    // Rail alone, high-DPI, for close inspection of the left panel.
    render("rail", 224, 540, dark = true, density = 2f) {
        dev.koda.desktop.ui.Rail(model, AppView.Home, {}, Modifier.fillMaxSize())
    }

    kotlin.system.exitProcess(0)
}
