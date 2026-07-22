package dev.koda.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.koda.protocol.AssistantMessage
import dev.koda.protocol.ErrorEvent
import dev.koda.protocol.Notice
import dev.koda.protocol.SessionStarted
import dev.koda.protocol.TextDelta
import dev.koda.protocol.ToolBegin
import dev.koda.protocol.ToolEnd
import dev.koda.protocol.TurnCompleted
import dev.koda.protocol.TurnStarted
import dev.koda.protocol.UserTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** One rendered line in the transcript. */
sealed interface Line {
    val key: Int
    data class User(override val key: Int, val text: String) : Line
    data class Assistant(override val key: Int, val text: String) : Line
    data class Tool(override val key: Int, val text: String, val error: Boolean) : Line
    data class Note(override val key: Int, val text: String) : Line
}

/**
 * Folds the daemon's [Event] stream into observable Compose state. This is the
 * whole "off the UI thread → immutable state" story for increment 1; richer
 * rendering (tool timeline, delta coalescing) layers on later.
 */
class AppModel(private val client: DaemonClient, private val scope: CoroutineScope) {
    var status by mutableStateOf(ConnStatus.Connecting)
        private set
    var sessionId by mutableStateOf(UUID.randomUUID().toString().take(8))
    var working by mutableStateOf(false)
        private set
    var streaming by mutableStateOf("")
        private set
    val lines = mutableStateListOf<Line>()

    private var seq = 0
    private fun add(make: (Int) -> Line) { lines.add(make(seq++)) }

    init {
        client.onStatus = { status = it }
        scope.launch {
            client.events.collect { ev ->
                when (ev) {
                    is SessionStarted -> {}
                    is TurnStarted -> if (ev.sessionId == sessionId) streaming = ""
                    is TextDelta -> if (ev.sessionId == sessionId) streaming += ev.text
                    is AssistantMessage -> {
                        if (ev.text.isNotBlank()) add { Line.Assistant(it, ev.text) }
                        streaming = ""
                    }
                    is ToolBegin -> {
                        streaming = ""
                        add { Line.Tool(it, "⚙ ${ev.toolName}  ${ev.argsJson.take(120)}", false) }
                    }
                    is ToolEnd -> add {
                        val mark = if (ev.isError) "✗" else "✓"
                        Line.Tool(it, "$mark ${ev.toolName}: ${ev.output.lineSequence().firstOrNull()?.take(110) ?: ""}", ev.isError)
                    }
                    is Notice -> add { Line.Note(it, ev.text) }
                    is ErrorEvent -> add { Line.Note(it, "error: ${ev.message}") }
                    is TurnCompleted -> { working = false; streaming = "" }
                    else -> {}
                }
            }
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        add { Line.User(it, t) }
        working = true
        scope.launch { client.submit(UserTurn(UUID.randomUUID().toString(), sessionId, t)) }
    }
}
