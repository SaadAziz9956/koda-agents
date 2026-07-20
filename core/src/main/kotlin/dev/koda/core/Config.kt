package dev.koda.core

import java.nio.file.Path

enum class PermissionMode {
    /** Mutating tools require user approval. */
    DEFAULT,

    /** File edits auto-approved; shell commands still ask. */
    ACCEPT_EDITS,

    /** Nothing asks. The user opted out of the gate entirely. */
    YOLO,
}

/** Which vendor API shape the provider speaks. Koog client selection keys off this. */
enum class ApiShape { ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS }

data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val apiShape: ApiShape,
)

data class KodaConfig(
    val provider: ProviderConfig,
    val model: String,
    val cwd: Path,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
    val maxIterationsPerTurn: Int = 50,
    val maxTokens: Int = 8192,
    val kodaHome: Path = System.getenv("KODA_HOME")?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".koda"),
) {
    val sessionsDir: Path get() = kodaHome.resolve("sessions")
}
