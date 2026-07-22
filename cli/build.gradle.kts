plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":client"))
    implementation(project(":core"))
    implementation(libs.coroutines.core)
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass.set("dev.koda.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
