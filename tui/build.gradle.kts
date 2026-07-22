plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":client"))
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.mosaic.runtime)
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("dev.koda.tui.MainKt")
}
