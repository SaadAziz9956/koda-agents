package dev.koda.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local scheduled tasks and `/loop`: fire a prompt into a session on a repeating
 * interval. Tasks persist to ~/.koda/schedules.json and resume when the daemon
 * restarts (they run only while it's up — no managed cloud). Firing goes through
 * [fire] as a normal turn, so unattended runs honor the session's permission
 * mode (use accept-edits/yolo for hands-off tasks).
 */
class Scheduler(
    private val scope: CoroutineScope,
    kodaHome: Path,
    private val fire: suspend (sessionId: String, prompt: String) -> Unit,
) {
    @Serializable
    data class Task(val id: String, val sessionId: String, val prompt: String, val everySeconds: Long)

    private val file: Path = kodaHome.resolve("schedules.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val tasks = ConcurrentHashMap<String, Task>()
    private val jobs = ConcurrentHashMap<String, Job>()

    fun loadAndStart() {
        runCatching {
            if (Files.exists(file)) {
                json.decodeFromString<List<Task>>(Files.readString(file)).forEach { tasks[it.id] = it; launch(it) }
            }
        }
    }

    fun create(sessionId: String, prompt: String, everySeconds: Long): List<Task> {
        val task = Task(UUID.randomUUID().toString().take(8), sessionId, prompt, everySeconds.coerceAtLeast(10))
        tasks[task.id] = task
        launch(task)
        persist()
        return list()
    }

    fun cancel(id: String): List<Task> {
        jobs.remove(id)?.cancel()
        tasks.remove(id)
        persist()
        return list()
    }

    fun list(): List<Task> = tasks.values.sortedBy { it.id }

    private fun launch(task: Task) {
        jobs[task.id] = scope.launch {
            while (isActive) {
                delay(task.everySeconds * 1000)
                runCatching { fire(task.sessionId, task.prompt) }
            }
        }
    }

    private fun persist() {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, json.encodeToString(tasks.values.toList()))
        }
    }
}
