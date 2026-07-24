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

    fun render(name: String, w: Int, h: Int, dark: Boolean, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
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
    render("home", 924, 540, dark = true) {
        AppShell(model, onOpenPalette = {}, onOpenSettings = {}, dark = true, onToggleTheme = {}, initialView = AppView.Home)
    }
    render("code", 924, 540, dark = true) {
        AppShell(model, onOpenPalette = {}, onOpenSettings = {}, dark = true, onToggleTheme = {}, initialView = AppView.Code)
    }
    render("home-light", 924, 540, dark = false) {
        AppShell(model, onOpenPalette = {}, onOpenSettings = {}, dark = false, onToggleTheme = {}, initialView = AppView.Home)
    }

    kotlin.system.exitProcess(0)
}
