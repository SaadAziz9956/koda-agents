package dev.koda.gateway

import dev.koda.client.StartupException
import dev.koda.client.resolveStartup
import dev.koda.core.KodaCore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Koda WhatsApp gateway — a daemon-ready surface that lets you talk to your
 * agent from your phone. It embeds the same [KodaCore] the CLI/TUI use and
 * exposes a Meta "Cloud API" webhook:
 *
 *   GET  /webhook  — Meta's subscription handshake (echoes hub.challenge)
 *   POST /webhook  — inbound messages (HMAC-verified, then routed to the core)
 *
 * Meta pushes to a public HTTPS URL, so in local dev you front this with a
 * tunnel (cloudflared/ngrok). Provider creds come from the shared client
 * startup; WhatsApp creds and the sender allowlist come from the environment.
 */
private val WA_JSON = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    val startup = try {
        resolveStartup(args)
    } catch (e: StartupException) {
        System.err.println("koda-gateway: ${e.message}")
        kotlin.system.exitProcess(1)
    }

    val phoneNumberId = env("WHATSAPP_PHONE_NUMBER_ID")
    val accessToken = env("WHATSAPP_ACCESS_TOKEN")
    val appSecret = env("WHATSAPP_APP_SECRET")
    val verifyToken = env("WHATSAPP_VERIFY_TOKEN")
    val apiVersion = env("KODA_WHATSAPP_API_VERSION").ifBlank { "v21.0" }
    val allowed = env("KODA_WHATSAPP_ALLOWED").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val host = env("KODA_GATEWAY_HOST").ifBlank { "127.0.0.1" }
    val port = env("KODA_GATEWAY_PORT").toIntOrNull() ?: 8080

    // Fail fast on the two things that would make the gateway unsafe or useless.
    if (allowed.isEmpty()) {
        System.err.println("koda-gateway: KODA_WHATSAPP_ALLOWED must list at least one wa_id (comma-separated). Refusing to run an open gateway.")
        kotlin.system.exitProcess(1)
    }
    if (verifyToken.isBlank()) {
        System.err.println("koda-gateway: WHATSAPP_VERIFY_TOKEN is required for the webhook handshake.")
        kotlin.system.exitProcess(1)
    }
    if (appSecret.isBlank()) {
        System.err.println("koda-gateway: WARNING — WHATSAPP_APP_SECRET unset; inbound signatures will NOT be verified (dev only).")
    }
    if (phoneNumberId.isBlank() || accessToken.isBlank()) {
        System.err.println("koda-gateway: WARNING — WhatsApp send credentials unset; running in dry-run (replies logged, not sent).")
    }

    val core = KodaCore.create(startup.config)
    core.start()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = WhatsAppClient(apiVersion, phoneNumberId, accessToken)
    val gateway = WhatsAppGateway(core, client, allowed, scope)
    gateway.start()

    println("koda gateway (whatsapp) listening on http://$host:$port/webhook — ${allowed.size} allowed sender(s)")

    embeddedServer(CIO, port = port, host = host) {
        routing {
            // Subscription handshake.
            get("/webhook") {
                val mode = call.request.queryParameters["hub.mode"]
                val token = call.request.queryParameters["hub.verify_token"]
                val challenge = call.request.queryParameters["hub.challenge"]
                if (mode == "subscribe" && token == verifyToken && challenge != null) {
                    call.respondText(challenge)
                } else {
                    call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                }
            }

            // Inbound messages.
            post("/webhook") {
                val raw = call.receiveText()
                if (appSecret.isNotBlank() && !signatureValid(appSecret, raw, call.request.headers["X-Hub-Signature-256"])) {
                    call.respondText("bad signature", status = HttpStatusCode.Forbidden)
                    return@post
                }
                // Always ack fast so Meta doesn't retry; process after.
                call.respondText("ok")
                runCatching { dispatch(raw, gateway) }
                    .onFailure { System.err.println("koda-gateway: inbound parse error: ${it.message}") }
            }
        }
    }.start(wait = true)
}

/** Parse an inbound webhook body and hand each text message to the gateway. */
private fun dispatch(raw: String, gateway: WhatsAppGateway) {
    val payload = WA_JSON.decodeFromString(InboundPayload.serializer(), raw)
    for (entry in payload.entry) {
        for (change in entry.changes) {
            for (message in change.value.messages) {
                if (message.type == "text" && message.from.isNotBlank()) {
                    gateway.onInboundText(message.from, message.text?.body.orEmpty())
                }
            }
        }
    }
}

/** Verify Meta's `X-Hub-Signature-256: sha256=<hex>` over the raw body. */
private fun signatureValid(appSecret: String, body: String, header: String?): Boolean {
    val provided = header?.removePrefix("sha256=") ?: return false
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val expected = mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())
}

private fun env(name: String): String = System.getenv(name)?.trim().orEmpty()

// --- Minimal inbound webhook shape (only what we consume) ---

@Serializable
private data class InboundPayload(val entry: List<Entry> = emptyList())

@Serializable
private data class Entry(val changes: List<Change> = emptyList())

@Serializable
private data class Change(val value: Value = Value())

@Serializable
private data class Value(val messages: List<WaMessage> = emptyList())

@Serializable
private data class WaMessage(val from: String = "", val type: String = "", val text: WaText? = null)

@Serializable
private data class WaText(val body: String = "")
