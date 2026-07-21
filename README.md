# Koda

An autonomous personal agent harness in Kotlin — Hermes-class identity,
Codex-class architecture, built from the ground up (JetBrains Koog as the engine, the harness built around it).

```
┌─────────┐  Submission  ┌──────────────────────────────┐
│ CLI/TUI ├─────────────►│           KodaCore           │
│ daemon  │◄─────────────┤ loop · permissions · session │
│ (any    │    Event     │   providers · tools · store  │
│ surface)│              └──────────────────────────────┘
└─────────┘
```

## Quick start

```sh
export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY
./gradlew :cli:run -q --console=plain
```

Headless single turn:

```sh
./gradlew :cli:run -q --console=plain --args='-p "list the kotlin files here" --yolo'
```

TUI (Mosaic-rendered terminal surface — Compose-for-terminal: markdown replies,
scrolling transcript, pinned composer + status line, key-driven input):

```sh
./gradlew :tui:installDist -q && ./tui/build/install/tui/bin/tui
```

Run the TUI in a **real terminal** — Mosaic repaints via ANSI control codes,
which IDE/Gradle run consoles strip. Use the CLI (`:cli`) for IDE run windows.

Daemon (WebSocket surface for other clients):

```sh
./gradlew :daemon:run   # ws://127.0.0.1:4477/ws
```

## Options

| Flag | Meaning |
|---|---|
| `-p <text>` | one headless turn, then exit |
| `--model <id>` | model id (defaults per provider) |
| `--provider anthropic\|openai\|custom` | auto-detected from env keys |
| `--base-url <url>` | OpenAI-compatible endpoint for `custom` |
| `--session <id>` | resume a session |
| `--accept-edits` | auto-approve file edits (shell still asks) |
| `--yolo` | no approval prompts |

Sessions persist as JSONL under `~/.koda/sessions/`.

See [AGENTS.md](AGENTS.md) for the design constitution and roadmap.

## Sandboxing

Shell commands run inside an OS-level sandbox. On macOS this uses the built-in
Apple Seatbelt (`sandbox-exec`) — nothing to install. Under the default
`workspace-write` policy a command can read anywhere but can only **write** to
the working directory and temp, **`.git` is protected**, and **network is
denied**. Policies: `read-only`, `workspace-write` (default), `danger-full-access`
(no sandbox). Linux (bubblewrap) is planned; where unavailable, the shell falls
back to unsandboxed with a startup notice. This is OS-enforced containment, not
just the approval prompt — a command physically cannot escape the workspace.

## Hooks

Lifecycle subprocesses configured in `~/.koda/hooks.json` (global) or
`.koda/hooks.json` (project). Events: `SessionStart`, `UserPromptSubmit`,
`PreToolUse`, `PostToolUse`, `Stop`. Each hook receives a JSON event on stdin.

```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "bash", "command": "~/.koda/guard.sh" }
    ]
  }
}
```

A `PreToolUse` hook can influence the call: exit `2` (or stdout
`{"decision":"deny","reason":"…"}`) blocks it, `{"decision":"allow"}` bypasses
the approval prompt, and `{"input":{…}}` rewrites the tool arguments (built-in
tools only). `matcher` is a glob on the tool name (`*` = all).

## Development

Branching model:

- **`main`** — production. Stable, tested releases only; every commit is a shippable version.
- **`dev`** — integration. Work merges here first and is verified before promotion to `main`.
- **`feature/*`** — one branch per feature, cut from `dev`, merged back into `dev` when done.

Flow: `feature/x` → `dev` → `main`.

Build and run:

```sh
./gradlew build                          # compile all modules
./gradlew :cli:installDist               # produce cli/build/install/cli/bin/cli
python3 scripts/mock_llm.py              # offline test provider (no API key)
```
