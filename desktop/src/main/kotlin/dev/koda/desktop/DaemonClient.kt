package dev.koda.desktop

import dev.koda.protocol.Event
import dev.koda.protocol.ProtocolJson
import dev.koda.protocol.Submission
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

enum class ConnStatus { Disconnected, Connecting, Connected }

/**
 * The desktop app's only link to Koda: a WebSocket client that speaks the
 * protocol to a running daemon. Submissions queue in [outbox] and flush on
 * (re)connect; every inbound frame is decoded to an [Event]. The app depends
 * on nothing but `:protocol` — the narrow waist is the whole boundary.
 */
class DaemonClient(private val scope: CoroutineScope) {
    private val http = HttpClient(CIO) { install(WebSockets) }
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 512)
    val events: SharedFlow<Event> = _events
    private val outbox = Channel<Submission>(Channel.UNLIMITED)

    /** Set by the model to observe connection state. */
    var onStatus: (ConnStatus) -> Unit = {}

    private var job: Job? = null

    /** Connect to `base` (e.g. ws://127.0.0.1:4477) and auto-reconnect. Re-targets cleanly. */
    fun connect(base: String) {
        job?.cancel()
        job = scope.launch {
            while (true) {
                onStatus(ConnStatus.Connecting)
                try {
                    http.webSocket("$base/ws") {
                        onStatus(ConnStatus.Connected)
                        val sender = launch {
                            for (sub in outbox) {
                                send(Frame.Text(ProtocolJson.encodeToString(Submission.serializer(), sub)))
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    _events.emit(ProtocolJson.decodeFromString(Event.serializer(), frame.readText()))
                                }
                            }
                        } finally {
                            sender.cancel()
                        }
                    }
                } catch (_: Exception) {
                    // fall through to reconnect
                }
                onStatus(ConnStatus.Disconnected)
                delay(1500)
            }
        }
    }

    suspend fun submit(sub: Submission) = outbox.send(sub)
}
