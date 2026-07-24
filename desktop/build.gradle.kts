import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":protocol"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    runtimeOnly(libs.slf4j.nop)
}

// Compose Desktop's own application config — provides the `run` task that
// launches the window with correct macOS main-thread/AWT setup (the plain
// `application` plugin does not), plus native packaging.
// Offscreen screenshot harness — renders the real screens to PNGs with sample
// data so the UI can be diffed against the design handoff. Dev-only.
tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "Render app screens to build/screens/*.png via ImageComposeScene"
    mainClass.set("dev.koda.desktop.gallery.RenderScreensKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(layout.buildDirectory.dir("screens").get().asFile.absolutePath)
    systemProperty("skiko.renderApi", "SOFTWARE")
}

compose.desktop {
    application {
        mainClass = "dev.koda.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Koda"
            packageVersion = "1.0.0"
            description = "Koda — a personal agent, on your desktop."
            vendor = "Koda"
            macOS {
                bundleID = "dev.koda.desktop"
                iconFile.set(project.file("icons/Koda.icns"))
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
