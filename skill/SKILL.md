---
name: mirror
description: >
  Pair this Grok session to the Mirror iOS/Android app via a same-Wi-Fi QR
  code. Use when the user says pair, pairing, QR, QR code, session ID,
  mirror, Mirror app, show the QR, or /mirror.
---

# Mirror pairing (v3.0 - multiple sessions)

When the user wants to mirror **this** session to their phone:

1. Resolve the current Grok session id (the live conversation id). If unknown, look under `~/.grok/sessions/` for the newest directory matching this workspace.
2. Start the companion from the project (do not kill it until they are done):

```bash
cd ~/projects/grok-mirror
python3 -m mirror_companion pair --session <SESSION_ID> --cwd <WORKSPACE>
```

If they asked to lock the QR to their Apple ID (iPhone only; Android is rejected):

```bash
python3 -m mirror_companion pair --session <SESSION_ID> --cwd <WORKSPACE> --require-apple
```

The QR carries `apple=1` only — never an Apple ID or email. After scan, Mirror runs Sign in with Apple; the companion verifies Apple’s JWT and binds that `sub`. A stolen QR on another Apple ID (or any Android) is refused.

Use the real session id and the workspace cwd. Bind stays on the LAN (`0.0.0.0`, first free port from 8787). If Tailscale is up, the QR uses the Mac’s `100.x` / MagicDNS address so the phone can stay on cellular. A second Grok session on the same Mac needs its own `pair` (next free port) and its own `watch`. Do not expose the companion to the public internet. Do not add BLE.

3. Show the user the terminal QR (and `~/.grok/mirror/pair.png` if written). Tell them to tap **Scan QR** in the Mirror app (or use the system Camera — it opens `mirror://`). If `--require-apple`, they must confirm with their Apple ID on an iPhone.
4. Leave the companion process running in the background so the phone can poll and send. Do not print the pairing token again later in logs.
5. If this TUI is already running, the companion queues phone sends in `~/.grok/mirror/inbox/<SESSION_ID>.jsonl` instead of `grok --resume` (that would hang). After pairing, start a **persistent monitor** (do not print tokens):

```bash
cd ~/projects/grok-mirror
PYTHONUNBUFFERED=1 python3 -m mirror_companion watch --session <SESSION_ID>
```

Each `MIRROR …` stdout line is a phone turn. Treat that text as the user's message and reply in this session (the phone polls the session log). Then drain the inbox so lines are not applied twice. Also drain any leftover inbox at the start of a normal TUI turn. If the TUI is not running, phone sends use `grok --resume <SESSION_ID> --single …`.

Never disable ATS. Never invent a pairing protocol; this CLI is the source of truth.

Install for all workspaces: copy this file to `~/.grok/skills/mirror/SKILL.md`.
