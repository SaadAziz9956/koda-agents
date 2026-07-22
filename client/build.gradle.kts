plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Exposed transitively so surfaces depending on :client also see the core
    // config types (KodaConfig, ProviderConfig) and the protocol.
    api(project(":core"))
}
