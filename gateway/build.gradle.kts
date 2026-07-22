plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":client"))
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("dev.koda.gateway.MainKt")
}
