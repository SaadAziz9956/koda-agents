# Koda WhatsApp Gateway

Talk to your Koda agent from WhatsApp. The gateway is a Koda *surface* — it
embeds the same core the CLI and TUI use and exposes a Meta **WhatsApp Business
Cloud API** webhook. It is not a Telegram-style bot: WhatsApp has no free
long-poll API, so Meta *pushes* inbound messages to a public HTTPS URL you host.

## What you need from Meta (one-time)

1. A **Meta developer app** (https://developers.facebook.com) with the
   **WhatsApp** product added.
2. A **WhatsApp Business Account (WABA)** and a **test or production phone
   number**. The app gives you a **Phone Number ID** and a temporary access
   token; for anything lasting, create a **System User** permanent token.
3. Your app's **App Secret** (App Settings → Basic) — used to verify that
   inbound webhooks really came from Meta.
4. A **Verify Token** — any string you choose; Meta echoes it back during the
   webhook handshake so both sides agree.

## Configuration (environment)

| Variable | Required | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` (or `OPENAI_API_KEY`) | ✓ | The model provider (shared with CLI/TUI). |
| `KODA_WHATSAPP_ALLOWED` | ✓ | Comma-separated `wa_id`s (phone numbers, digits only) allowed to drive the agent. **Anyone not listed is ignored.** The gateway refuses to start if this is empty. |
| `WHATSAPP_VERIFY_TOKEN` | ✓ | The handshake token you set in Meta's webhook config. |
| `WHATSAPP_APP_SECRET` | strongly recommended | HMAC key for verifying inbound signatures. If unset, signatures are **not** checked (dev only). |
| `WHATSAPP_PHONE_NUMBER_ID` | to send | The sending number's ID. If unset, replies are logged, not sent (dry-run). |
| `WHATSAPP_ACCESS_TOKEN` | to send | Bearer token for the Graph API. |
| `KODA_WHATSAPP_API_VERSION` | optional | Graph API version (default `v21.0`). |
| `KODA_GATEWAY_HOST` | optional | Bind host (default `127.0.0.1`). |
| `KODA_GATEWAY_PORT` | optional | Bind port (default `8080`). |

## Run it

```sh
./gradlew :gateway:installDist -q

export ANTHROPIC_API_KEY=sk-ant-...
export KODA_WHATSAPP_ALLOWED="15551234567"       # your own number, digits only
export WHATSAPP_VERIFY_TOKEN="pick-any-string"
export WHATSAPP_APP_SECRET="<app secret>"
export WHATSAPP_PHONE_NUMBER_ID="<phone number id>"
export WHATSAPP_ACCESS_TOKEN="<access token>"

./gateway/build/install/gateway/bin/gateway
```

The gateway binds locally. Expose it to Meta with a tunnel:

```sh
cloudflared tunnel --url http://127.0.0.1:8080
# or: ngrok http 8080
```

Then in Meta's app → WhatsApp → Configuration, set the **Callback URL** to
`https://<your-tunnel>/webhook` and the **Verify token** to your
`WHATSAPP_VERIFY_TOKEN`, and subscribe to the **messages** field.

## How it behaves

- **One session per contact** (`wa-<wa_id>`), so conversations persist and
  resume across restarts — same session store as the CLI/TUI.
- **Approvals come as chat messages.** When the agent wants to run a gated tool,
  you get a message; reply **yes** (allow once), **always** (allow that tool
  from now on), or **no** (deny). No silent auto-approval over the network.
- **Inbound text is sanitized** (invisible-unicode / injection stripping) before
  it reaches the core, same as every other surface.
- **Long replies are split** to WhatsApp's message-length limit.

## Local testing without Meta

The webhook and security layers can be exercised with `curl` (dry-run send):

```sh
# handshake
curl "http://127.0.0.1:8080/webhook?hub.mode=subscribe&hub.verify_token=$WHATSAPP_VERIFY_TOKEN&hub.challenge=OK"
# inbound message (omit the signature only when WHATSAPP_APP_SECRET is unset)
curl -X POST http://127.0.0.1:8080/webhook -H 'Content-Type: application/json' \
  -d '{"entry":[{"changes":[{"value":{"messages":[{"from":"15551234567","type":"text","text":{"body":"hi"}}]}}]}]}'
```
