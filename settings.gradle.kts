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

include("protocol", "tools", "core", "cli", "daemon", "tui")
