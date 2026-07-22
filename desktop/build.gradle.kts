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
compose.desktop {
    application {
        mainClass = "dev.koda.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Koda"
            packageVersion = "1.0.0"
        }
    }
}
