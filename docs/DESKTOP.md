# Koda Desktop — build plan

A **Compose Multiplatform** desktop surface for Koda. Desktop first; the token
system and most composables are written to carry over to Android/iOS later.

## Principles

- **Thin protocol client.** The app depends only on `:protocol` — never `:core`
  or Koog. It talks to a running **daemon over WebSocket** (`ws://host:port/ws`),
  sending `Submission` JSON and receiving `Event` JSON, exactly as the daemon
  already speaks. The narrow waist is the only boundary.
- **Material 3 Expressive as engine, Ember as skin.** `MaterialExpressiveTheme`
  supplies motion-physics, a11y, and adaptive layout; we feed it the Ember
  `ColorScheme`, custom typography (sans for chat, mono for the agent's work),
  and a **tightened shape scale** (8–14dp, not M3's pills). Signature surfaces
  are custom composables, not stock M3.
- **Smooth by construction.** Protocol events fold into immutable UI state off
  the UI thread; token deltas are coalesced to one frame; the transcript is a
  `LazyColumn` with stable keys so only the streaming message recomposes.

## Module layout (`:desktop`)

```
desktop/
  build.gradle.kts            compose + kotlin.compose + ktor client
  src/main/kotlin/dev/koda/desktop/
    Main.kt                   application {} entry + Window
    DaemonClient.kt           Ktor WebSocket client ↔ protocol
    AppModel.kt               event→state fold (connection, transcript, streaming)
    theme/Ember.kt            Ember ColorScheme (dark+light), shapes, type, KodaTheme
    ui/AppShell.kt            top bar · rail · conversation · composer
    ui/*                      (later) ToolTimeline, ApprovalCard, RewindPanel, CommandPalette
```

## Increments (each lands on `dev`)

1. **Scaffold** — module, CMP/Expressive toolchain, Ember theme, `DaemonClient`,
   a minimal shell that connects and renders a live turn (text + tools). *Builds
   and launches.*  ← this increment
2. **Conversation fidelity** — streaming caret, the custom **tool timeline**,
   markdown, token/context ring. Delta coalescing.
3. **Control** — inline **approval cards** (Y/A/N), **/rewind** panel + confirm,
   **⌘K** command palette.
4. **Onboarding & config** — daemon connect/empty/reconnect screen, settings.

## Connection model

- Default target `ws://127.0.0.1:4477/ws`; editable in Connect/Settings.
- Auto-reconnect with backoff; connection status surfaced in the top bar.
- One daemon = one Koda; sessions listed via the existing `ListSessions`
  submission. The desktop is just another surface on the same agent.

## Verification

- `./gradlew :desktop:build` compiles/assembles on CI.
- Live: `./gradlew :daemon:run` in one shell, `./gradlew :desktop:run` in
  another — the window connects and a turn streams. (GUI can't be verified
  headlessly; run locally.)
