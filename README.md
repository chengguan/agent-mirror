# grok-mirror

**Mirror** — **v1.0 - private network edition**

Unofficial same-Wi-Fi companion: the Mac hosts this Grok Build session; the
iPhone/Android app scans a QR and shows the thread so you can keep talking
on the move.

Not an official xAI product. The Mac must stay on. v1 is private-network only.

## Pieces

| Piece | Path |
|---|---|
| Mac companion | `companion/` (`python3 -m companion pair --session <id>`) |
| Phone app | `androidApp/` + `iosApp/` + `shared/` |
| Skill (all workspaces) | `~/.grok/skills/grok-mirror/SKILL.md` |

Home screen name: **Mirror**.

## Pair

On the Mac (Grok will do this when you say “pair” / “QR”):

```bash
cd ~/projects/grok-mirror
python3 -m companion pair --session <SESSION_ID> --cwd ~
```

Scan the QR with Mirror (or Camera). Keep that process running.

Phone sends the next turn with `grok --resume <id> --single …`. Avoid typing
in the TUI at the same time.

## Security (Agents.md)

- Pairing secret + TLS; phone pins the cert SHA-256
- Token in `Authorization: Bearer` (constant-time compare)
- Secrets on the phone go in Keychain / Keystore
- Biometric unlock
- No ATS global disable; only the pinned companion cert is trusted
- Message size and session-id checks
- Do not commit `~/.grok/mirror/` (certs and QR)

## Build

Android Studio: open `~/projects/grok-mirror`. JDK is Android Studio’s JBR.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :shared:iosSimulatorArm64Test :androidApp:assembleDebug
```

iOS: open `iosApp/iosApp.xcodeproj` (needs a team id in `iosApp/Configuration/Config.xcconfig`).

Companion tests:

```bash
python3 -m pytest
```

## Out of scope (v1)

- Off-LAN / Tailscale
- BLE
- Official Grok cloud session sync
- In-app camera QR scanner (use the system Camera, or paste the URL)
