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
