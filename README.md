# Mirror

**Mirror** — **v3.0 - multiple sessions**

Unofficial companion: the Mac hosts a Grok Build CLI session; the iPhone/Android
app scans a QR and shows the thread so you can keep talking on the move.
Same Wi-Fi, or Tailscale when the phone is on mobile data.

The Mac process is **mirror-companion** (`python3 -m mirror_companion`). That
name is reserved for a later PyPI upload; it has not been published yet.

Not an official xAI product. The Mac must stay awake. Do not expose the
companion to the public internet.

## Pieces

| Piece | Path |
|---|---|
| Mac companion | `mirror_companion/` (`python3 -m mirror_companion pair --session <id>`) |
| Phone app | `androidApp/` + `iosApp/` + `shared/` |
| Skill (all workspaces) | `~/.grok/skills/mirror/SKILL.md` |

Home screen name: **Mirror**.

## Pair

On the Mac (Grok will do this when you say “pair” / “QR”):

```bash
cd ~/projects/grok-mirror
python3 -m mirror_companion pair --session <SESSION_ID> --cwd ~
```

Tap **Scan QR** in Mirror and point the camera at the terminal QR, tap
**Choose from Photos** to pick a saved QR image, or paste the pairing URL.
The system Camera still opens `mirror://` if you prefer. Keep that
process running.

Phone sends the next turn with `grok --resume <id> --single …` (the Mac's
locally installed Grok Build CLI). Avoid typing in the TUI at the same time.

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

Optional: lock the QR to an Apple ID (`--require-apple`). iPhone only;
Android is rejected. The QR does not contain the Apple ID. Sign in with
Apple needs a paid Apple Developer Program team (a Personal Team cannot
enable that capability).

## Out of scope (v1)

- Public internet / Funnel / port-forward (Tailscale mesh is supported)
- BLE
- Official Grok cloud session sync
