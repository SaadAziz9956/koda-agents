package dev.koda.core

import dev.koda.providers.ProviderConfig
import java.nio.file.Path

enum class PermissionMode {
    /** Mutating tools require user approval. */
    DEFAULT,

    /** File edits auto-approved; shell commands still ask. */
    ACCEPT_EDITS,

    /** Nothing asks. The user opted out of the gate entirely. */
    YOLO,
}

data class KodaConfig(
    val provider: ProviderConfig,
    val model: String,
    val cwd: Path,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
    val maxIterationsPerTurn: Int = 50,
    val maxTokens: Int = 8192,
    val kodaHome: Path = Path.of(System.getProperty("user.home"), ".koda"),
) {
    val sessionsDir: Path get() = kodaHome.resolve("sessions")
}
