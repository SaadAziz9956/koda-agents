package dev.koda.providers

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line

/**
 * A provider transport converts the normalized [LlmRequest] into one vendor
 * API call, streams deltas out through [onDelta], and returns the finalized
 * normalized response. One implementation per API *shape*, not per vendor —
 * every OpenAI-compatible host reuses [OpenAiTransport].
 */
interface ProviderTransport : AutoCloseable {
    val providerName: String

    suspend fun complete(
        request: LlmRequest,
        onDelta: suspend (StreamDelta) -> Unit,
    ): LlmResponse

    override fun close() {}
}

data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val apiShape: ApiShape,
)

enum class ApiShape { ANTHROPIC_MESSAGES, OPENAI_CHAT_COMPLETIONS }

fun createTransport(config: ProviderConfig): ProviderTransport = when (config.apiShape) {
    ApiShape.ANTHROPIC_MESSAGES -> AnthropicTransport(config)
    ApiShape.OPENAI_CHAT_COMPLETIONS -> OpenAiTransport(config)
}

internal fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 600_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 600_000
    }
    expectSuccess = false
}

/**
 * POSTs [body] to [url] and feeds each SSE `data:` payload to [onData]
 * until the stream ends. Throws [ProviderException] on non-2xx.
 */
internal suspend fun HttpClient.postSse(
    url: String,
    body: String,
    configure: HttpRequestBuilder.() -> Unit,
    onData: suspend (String) -> Unit,
) {
    preparePost(url) {
        contentType(ContentType.Application.Json)
        headers { append("Accept", "text/event-stream") }
        configure()
        setBody(body)
    }.execute { response: HttpResponse ->
        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            throw ProviderException(
                "HTTP ${response.status.value}: ${errorBody.take(2000)}",
                statusCode = response.status.value,
            )
        }
        val channel = response.bodyAsChannel()
        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trim()
                if (payload.isNotEmpty() && payload != "[DONE]") onData(payload)
            }
        }
    }
}
