plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))
    api(project(":tools"))
    api(libs.koog.agents)
    api(libs.koog.agents.mcp)
    api(libs.coroutines.core)
    implementation(libs.serialization.json)
    // Ktor client for the per-request API-key injector + key validation.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
}
