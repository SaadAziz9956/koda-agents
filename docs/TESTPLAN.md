# Koda — Extensive Test Plan (pre-v1.0)

Everything on `dev`. Mark each ✅/❌. Tags:
- **[live]** needs a real API key — exercises real model behavior.
- **[term]** must run in a REAL terminal (the Mosaic TUI won't render in an IDE run window).
- **[linux]** needs a Linux host (bubblewrap) — unverifiable on macOS.

## 0. Setup

```sh
cd ~/Projects/IdeaProjects/koda && git checkout dev && git pull
./gradlew build -x test :cli:installDist :tui:installDist -q
export ANTHROPIC_API_KEY=sk-ant-...          # for [live]
CLI=./cli/build/install/cli/bin/cli
TUI=./tui/build/install/tui/bin/tui
```

Testbed for context/hooks/sandbox checks:
```sh
TB=~/koda-testbed; rm -rf $TB && mkdir -p $TB/sub $TB/.koda && cd $TB && git init -q
printf 'PROJECT_RULE: begin replies with [koda].\n\n@style.md\n' > KODA.md
printf 'STYLE_RULE: at most 3 sentences.\n' > style.md
printf 'LOCAL_RULE: call me Saad.\n' > CLAUDE.local.md
printf 'SUBRULE: files under sub/ are legacy — flag as risky.\n' > sub/AGENTS.md
printf 'fun legacy() = TODO()\n' > sub/legacy.kt
cat > .koda/hooks.json <<'EOF'
{ "hooks": {
  "PreToolUse": [
    { "matcher": "write", "command": "echo '{\"decision\":\"allow\"}'" },
    { "matcher": "bash",  "command": "grep -q 'rm -rf' && { echo blocked >&2; exit 2; } || exit 0" }
  ],
  "Stop": [ { "command": "echo tick >> /tmp/koda_stop.log" } ]
} }
EOF
```

---

## 1. Core loop, streaming, tools  [live]
- [ ] `explore this repo and summarize in 3 sentences` → streams; uses read/grep/glob; coherent.
- [ ] `create hello.txt with a haiku` → write tool; file appears.
- [ ] `read hello.txt then change "the" to "a"` → edit (exact replace); verify change.
- [ ] `run: ls -la | head` → bash runs, output shown.
- [ ] `find every .kt file that defines a tool` → grep/glob.
- [ ] `add three items to a todo list and mark one done` → todo renders.

## 2. Permissions  [live]
- [ ] default mode: a write → approval prompt; answer **n** → model told denied, no file.
- [ ] same write → **y** → succeeds.
- [ ] **a** (always) on a bash command → second same-tool call doesn't re-prompt.
- [ ] `--yolo` → no prompts at all.
- [ ] `--accept-edits` → file edits silent, bash still prompts.

## 3. Sessions & compaction  [live]
- [ ] note the session id; have a 2-turn convo; `exit`; relaunch `--session <id>` → `what did we discuss?` recalls.
- [ ] `/sessions` (TUI) or resume flow (CLI) lists it with message count.
- [ ] `/resume <wrong-id>` → rejected (not silent-empty).
- [ ] after several turns, `/compact` → reports `N → M messages` with **M < N**.
- [ ] `/usage` shows context % climbing over turns (real window, e.g. of 1,000,000).

## 4. Context files (v0.7 + v0.9)  [live, from testbed]
Run `$CLI` (or `$TUI`) with cwd = `~/koda-testbed`:
- [ ] `what are your rules and what do you call me?` → reply starts **[koda]**, ≤3 sentences, calls you **Saad** (hierarchy + @import + .local all at once).
- [ ] `read sub/legacy.kt` then `anything special about it?` → knows sub/ is legacy (subtree lazy-load).

## 5. Skills (v0.5)  [live]
- [ ] `/skills` lists ~177.
- [ ] `/codebase-inspection` → loads skill, installs pygount (approve), runs real LOC analysis.
- [ ] a skill you pick by name loads and is followed.

## 6. MCP (v0.4)  [live]
```sh
mkdir -p ~/koda-testbed/.koda
cat > ~/koda-testbed/.koda/mcp.json <<'EOF'
{"mcpServers":{"fs":{"command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/tmp"]}}}
EOF
```
- [ ] startup notice "mcp: connected 'fs'"; `/mcp` lists it + its tools.
- [ ] `use the fs server to list /tmp` → an MCP tool runs through the approval gate.

## 7. Subagents (v0.6)  [live]
- [ ] `use a subagent to find all tool classes` → `⤷ delegating` … `⤶ subagent done`; read-only.
- [ ] `delegate X, giving it read and bash tools` → subagent uses the named set; mutating still prompts.

## 8. Hooks (v0.8)  [live, from testbed]
- [ ] `create notes.txt with "hi"` → **no approval prompt** (PreToolUse allow bypass).
- [ ] `run: rm -rf /tmp/koda_fake` → **blocked** ("blocked by policy").
- [ ] after turns: `cat /tmp/koda_stop.log` → one `tick` per turn.

## 9. Sandboxing (v0.11)  [live]
macOS:
- [ ] workspace write succeeds; write to `~` **blocked** (no leak); write to `.git` **blocked**; network **blocked**.
- [ ] escalation: default mode, blocked command → "⚠ run WITHOUT sandbox" prompt → **n** stays blocked / **y** re-runs.
- [ ] `--yolo` auto-escalates (blocked cmd re-runs unsandboxed).
- [ ] startup notice shows `sandbox: seatbelt (workspace_write)`.
- [ ] **[linux]** on a Linux box: same block behaviors; notice shows `sandbox: bubblewrap`. **UNVERIFIED — must test.**

## 10. TUI (v0.10)  [term]
In a real terminal (`$TUI`):
- [ ] welcome banner renders; status footer shows model + context %.
- [ ] composer: type, **←/→** move, insert mid-word, **Backspace/Delete**, **↑/↓** history; block cursor sits where you type.
- [ ] your messages appear in the transcript (echoed), replies render as **markdown**.
- [ ] press **/** → command palette filters; **↑/↓** select, **Tab** complete, **Enter** run, **Esc** dismiss.
- [ ] `/skills`, `/sessions`, `/compact`, `/model`, `/yolo`, `/clear` all act.
- [ ] approval prompt appears inline; y/a/n works.
- [ ] **Ctrl-C** mid-reply → `(interrupted)`, session intact, process alive.
- [ ] resize the window → box re-fits width.

## 11. Interrupt (v0.2)  [term]
- [ ] long task → **Ctrl-C** mid-stream → stops cleanly, keeps session.

## 12. Providers  [live]
- [ ] Anthropic (default) works.
- [ ] `--provider custom --base-url <openai-compatible>` (e.g. a local/OpenRouter endpoint) works.

## 13. WhatsApp gateway (v0.14)  [live]
Local (no Meta needed), `KODA_WHATSAPP_ALLOWED=<id>`, `WHATSAPP_VERIFY_TOKEN=t`:
- [ ] GET `/webhook?hub.mode=subscribe&hub.verify_token=t&hub.challenge=X` → echoes `X`; wrong token → 403.
- [ ] with `WHATSAPP_APP_SECRET` set: POST with no/bad `X-Hub-Signature-256` → 403; valid HMAC → 200.
- [ ] POST inbound from a non-allowlisted `from` → dropped (logged), no turn.
- [ ] POST inbound from an allowlisted `from` → a turn runs; reply logged (dry-run) or sent (creds set).
End-to-end [live, Meta]:
- [ ] via a tunnel + real creds: message the number → agent replies; a gated tool → chat asks yes/no.

## 14. Rewind (v0.15)  [live]
- [ ] `/rewind` with no turns yet → "nothing to rewind".
- [ ] one turn that creates `foo.txt`, then `/rewind` → conversation drops back a turn and `foo.txt` is gone.
- [ ] a turn that edits an existing file, then `/rewind` → file content is restored to before the turn.
- [ ] two turns editing files, `/rewind 2` → both reverted; message reports turns undone + files reverted + message count.
- [ ] (known limit) a file changed via the **bash** tool is *not* reverted by rewind.

## 15. Daemon (optional)  [live]
- [ ] `./gradlew :daemon:run` → connect with `websocat ws://127.0.0.1:4477/ws`, send a `user_turn` frame, observe event stream.

---

## Division of labor
- **You run [live]** (needs a key): §1–§9, §12, §14.
- **You run [term]** (real terminal): §10, §11.
- **Linux box / CI:** §9 [linux].
