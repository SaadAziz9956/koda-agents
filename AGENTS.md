# Koda — Design Constitution

Koda is a personal autonomous agent harness in Kotlin: Hermes-shaped identity
(multi-surface personal agent), Codex-shaped architecture (core + typed
protocol, every surface a thin client), Claude-Code-shaped tool semantics
(exact-string Edit, read-before-edit, permission gate in front of dispatch),
with **JetBrains Koog as the engine** (LLM clients, functional-strategy agent
loop, tool runtime) — the harness around it is entirely ours.

## Design laws

These are ordered. When they conflict, the lower number wins.

1. **The protocol is the only boundary.** Surfaces (CLI, TUI, gateway,
   desktop, SDK) speak `Submission` in and `Event` out — nothing else.
   No surface may import `core` internals beyond constructing `KodaCore`.
   If a feature needs a new capability at the boundary, it gets a new
   protocol type, never a side channel.

2. **Prompt caching is sacred.** The system prompt is assembled once per
   session and stays byte-stable for the session's life. The conversation
   is append-only; nothing mutates past messages except explicit
   compaction (which splits the session). Any feature that would rewrite
   earlier context is designed around instead.

3. **Tools are dumb, the core is smart.** Tool handlers take JSON args and
   return strings. They never talk to the user, never render, never ask
   for permission — approval, display, and policy all live in the core,
   in front of dispatch.

4. **Narrow waist.** Every core tool schema ships on every LLM call, so new
   core tools are a last resort. Extend Koda in this order: prompt/skill →
   config → plugin (future) → MCP server (future) → core tool.

5. **The permission gate is honest.** Mutating tools ask unless the user
   opted out. Deny always wins. Nothing the model outputs can widen its
   own permissions.

6. **Kotlin-native stack: kotlinx + Ktor + Koog.** Koog is the engine —
   provider clients, the functional agent runtime, tool schemas, and
   (coming) MCP/compression/checkpoints. Koog types must not leak past
   `core`: the protocol, tools domain logic, and surfaces stay
   framework-free so the engine remains swappable. No Java frameworks,
   no reflection-based DI containers, no annotation processors.

7. **Ports and adapters, dependencies point inward.** The core depends on
   interfaces (`core/port/*`); implementations live in `core/adapter/*`
   (or their own modules) and are injected at a composition root
   (`KodaCore.create`, or the surface's main). No class constructs its own
   collaborators. Use cases (`AgentLoop`) are classes with injected
   dependencies, testable without I/O. State (`AgentSession`) is separated
   from orchestration (`SessionRuntime`) and from policy (ports).

## Module map

- `protocol` — wire types (`Submission`/`Event`), kotlinx.serialization. Zero deps beyond serialization.
- `tools` — framework-free domain tools: `KodaTool` interface + read, write, edit, bash, grep, glob, todo. No Koog imports here, ever.
- `core` — the use-case layer on Koog. `engine/KoogEngine` (per-turn functional-strategy AIAgent), `engine/ToolGate` (permission gate + events in front of every dispatch), `engine/KoogTools` (typed Koog bridges over the domain tools), `AgentSession` (state; conversation = Koog `Prompt`, committed only on successful turns), `ApprovalBroker`, `KodaCore` (facade + `SessionRuntime`), `core/port/*` (SessionRepository, PermissionPolicy), `core/adapter/*` (prompt-file repository, mode-based policy).
- `cli` — interactive terminal client + `-p` headless mode. Runs the core in-process, through the protocol boundary only.
- `daemon` — the same core served over WebSocket (`ws://127.0.0.1:4477/ws`) for future surfaces.

## Conventions

- Kotlin official style; JVM toolchain 21.
- Provider adapters normalize *everything* — no vendor types escape `providers`.
- Tool results are what the model sees: plain strings, truncated with an
  explicit marker when capped.
- Build: `./gradlew build`. Run: `./gradlew :cli:run -q --console=plain`.

## Roadmap (post-v0)

Interrupt key handling (Esc) → compaction → session browser/resume UX →
TUI (Mosaic or JLine) → MCP client → skills (SKILL.md standard) → hooks →
subagents → sandboxing (`sandbox-exec`/`bwrap` shell-out) → gateway surfaces →
the learning loop (memory files, background review, skill creation).
