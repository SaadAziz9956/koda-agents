package dev.koda.gateway

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** WhatsApp text messages cap at 4096 characters; we split longer replies. */
private const val WA_MAX_BODY = 3800

/**
 * Outbound half of the WhatsApp gateway: posts text back to a contact through
 * the Meta Graph "Cloud API". Deliberately uses the JDK HTTP client so the
 * gateway adds no HTTP-client dependency. When credentials are absent it runs
 * in dry-run mode — logging what it *would* send — so the inbound/session
 * plumbing can be exercised locally without a Meta account.
 */
class WhatsAppClient(
    private val apiVersion: String,
    private val phoneNumberId: String,
    private val accessToken: String,
    private val log: (String) -> Unit = ::println,
) {
    private val http = HttpClient.newHttpClient()
    private val configured = phoneNumberId.isNotBlank() && accessToken.isNotBlank()

    /** Send [body] to [to] (a wa_id), chunked to WhatsApp's length limit. */
    fun sendText(to: String, body: String) {
        for (chunk in chunk(body)) sendChunk(to, chunk)
    }

    private fun sendChunk(to: String, body: String) {
        if (!configured) {
            log("whatsapp[dry-run] → $to: ${body.take(120)}${if (body.length > 120) "…" else ""}")
            return
        }
        val payload: JsonObject = buildJsonObject {
            put("messaging_product", "whatsapp")
            put("recipient_type", "individual")
            put("to", to)
            put("type", "text")
            putJsonObject("text") { put("body", body) }
        }
        val request = HttpRequest.newBuilder(
            URI.create("https://graph.facebook.com/$apiVersion/$phoneNumberId/messages"),
        )
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(JsonObject.serializer(), payload)))
            .build()
        runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onSuccess { resp ->
                if (resp.statusCode() !in 200..299) {
                    log("whatsapp send failed (${resp.statusCode()}): ${resp.body().take(300)}")
                }
            }
            .onFailure { log("whatsapp send error: ${it.message}") }
    }

    private fun chunk(text: String): List<String> {
        if (text.length <= WA_MAX_BODY) return listOf(text.ifBlank { "(no output)" })
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + WA_MAX_BODY, text.length)
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }
}
