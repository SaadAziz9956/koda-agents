rootProject.name = "koda"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("protocol", "providers", "tools", "core", "cli", "daemon")
