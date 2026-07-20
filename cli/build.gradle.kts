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
}

application {
    mainClass.set("dev.koda.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
