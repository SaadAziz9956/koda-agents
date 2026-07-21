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
        google() // Mosaic pulls transitive AndroidX/Compose artifacts hosted here
    }
}

include("protocol", "tools", "core", "cli", "daemon", "tui")
