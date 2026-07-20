plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":protocol"))
    api(project(":providers"))
    api(project(":tools"))
    api(libs.coroutines.core)
    implementation(libs.serialization.json)
}
