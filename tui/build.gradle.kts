plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.mordant)
    implementation(libs.mordant.markdown)
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("dev.koda.tui.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
