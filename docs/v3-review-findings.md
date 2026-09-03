# Mirror v3.0 — code & security review findings

Reviewed by Claude Code on 2026-08-16, against the uncommitted v3.0
multi-session diff (working tree vs. `origin/main` @ `abca334`). The four
code-review items below were fixed in the v3.0 release (per-session send
state + optimistic rollback, parallel poll with visible-first refresh,
serialized catalog/draft writes off the UI thread, and removal of
`deleteArchive()`). The security review remains the record of that pass.

Companion test suite passed (32/32) at review time. After the fixes:
`python3 -m pytest` (32 passed) and
`./gradlew :shared:iosSimulatorArm64Test :androidApp:assembleDebug`.

## Security review — clean

Full trust-boundary trace (pairing token, TLS pinning, per-session file
scoping, subprocess handling) across `mirror_companion/*.py` (then
`grok_companion/*.py`) and the Kotlin
`shared/` module found no HIGH/MEDIUM-confidence vulnerability introduced by
the v3 diff. Specifically verified safe: `choose_listen_port()`'s socket
reservation (fixes a TOCTOU port-steal race rather than opening one), the
per-session `apple_bind.<sid>.json` migration (scoped correctly, legacy file
read-only), the `grok --resume --single <text>` subprocess call (argv list,
not shell, so the phone's message text can't inject), and per-session TLS
pinning in `AndroidMirrorApi`/`IosMirrorApi` (fresh `SSLContext` per pairing,
no shared/global trust state across sessions).

One low-priority parity note, not filed as a finding: `AndroidProtectedFiles`
(new in this diff) writes saved-conversation archives as plain files in
app-private storage, while `IosProtectedFiles` applies
`NSFileProtectionComplete`. `allowBackup="false"` in the manifest rules out
the obvious extraction path, so this isn't exploitable without an
already-rooted device — but if you want platform parity, wrap the Android
write in `androidx.security.crypto.EncryptedFile` the way
`AndroidSecureStore` already wraps the pairing-token store.

## Code review — 4 findings (max effort, ranked by severity)

### 1. [correctness] Switching tabs mid-send drops failed-send rollback
**File:** `shared/src/commonMain/kotlin/dev/chengguan/mirror/MirrorViewModel.kt:211` (`send()`, lines 189–222)

`send()`'s completion handler only touches state when the session is still
active (`if (_state.value.activeId != sessionId) return@launch`), and
nothing ever rolls back the optimistic message appended at line 197
(`threads[sessionId] = optimistic`).

**Trigger:** Send "X" on session A (optimistic bubble appended,
`sending=true`). Before the POST resolves (Android's send timeout is 180s),
switch to session B's tab — `selectSession()` unconditionally resets
`sending=false` regardless of A's in-flight request. When A's send later
fails (Wi-Fi drop, sleeping Mac), line 210 restores "X" into A's persisted
draft, then line 211 skips clearing/marking the optimistic message
(`ChatMessage` has no failed/pending field). Returning to tab A shows "X" as
an indistinguishable already-sent bubble **and** pre-filled again in the
compose box — tapping Send resends a duplicate. If the companion has no live
TUI, returning to A while the first request is still in flight and sending
again can also hit `server.py`'s `BridgeState.busy` 409, surfaced as the
misleading "Is the Mac companion still running?" even though it's reachable.

**Suggested fix:** Track in-flight sends per session (e.g. a
`Set<String>`/`Map<String, Job>` of session IDs with a pending send) instead
of a single global `sending` boolean, and on failure either mark the
optimistic message as failed (add a status field to `ChatMessage`) or remove
it from `threads[sessionId]` so retry-from-draft doesn't create a duplicate.

### 2. [correctness] `poll()` refreshes all sessions sequentially, not in parallel
**File:** `MirrorViewModel.kt:336` (`poll()`, lines 330–342)

`for (rec in live) { refreshSession(rec, ...) }` awaits each paired
session's network round-trip one at a time instead of concurrently. This
loop is entirely new in v3 — the old single-session `refresh()` had nothing
to iterate.

**Trigger:** Pair 3 sessions where session #1 (earlier in the list) is
unreachable (Mac asleep) while actively viewing session #2. Every 2.5s poll
tick, `refreshSession(#1)` blocks on the ~10s connect timeout before
failing, delaying `refreshSession(#2)` — the chat actually on screen — by
10+ seconds each tick. One dead Mac stalls every other paired Mac.

**Suggested fix:** Fan the per-session refreshes out concurrently, e.g.
`live.map { rec -> viewModelScope.async { refreshSession(rec, ...) } }.awaitAll()`,
or at minimum refresh the active session first/independently of the
background sweep.

### 3. [efficiency] Tab switch does a synchronous encrypted-store commit
**File:** `MirrorViewModel.kt:134` (`selectSession()`, also `persistActiveDraft()`)

`selectSession()` calls `persistCatalog()` (and `persistActiveDraft()`)
synchronously on every tab switch. On Android this runs
`AndroidSecureStore.write`'s `EncryptedSharedPreferences.commit()` — a
blocking encrypt-then-fsync — directly on the caller's thread, which for a
Compose tap handler is the main/UI thread. Pre-v3 this write only happened
once, at initial pairing; now it's on the primary hot path of the tabs
feature.

**Suggested fix:** Move `persistCatalog()`/`persistDraft()` off the main
thread (`viewModelScope.launch(Dispatchers.IO) { ... }`) or switch
`AndroidSecureStore.write` to `.apply()`-style async commit where exact
durability isn't required per-call.

### 4. [simplification] `deleteArchive()` is unused dead code
**File:** `MirrorViewModel.kt:326`

```kotlin
fun deleteArchive() {
    requestUnpair()
}
```

Added in this diff, forwards to `requestUnpair()`, but nothing calls it —
`MirrorApp.kt`/`ChatScreen.kt` already wire the same
`DisconnectIconButton`/`onRequestUnpair` path for archived tabs directly
(confirmed via `grep -rn deleteArchive --include="*.kt" .` — zero other
matches).

**Suggested fix:** Delete the function, or if it was meant to be a distinct
archived-tab action, wire it up and remove the duplication with
`requestUnpair()`.

## Suggested order to tackle these

1. Fix #1 (send/tab-switch) first — it's the one with real user-visible data
   loss / duplicate-message risk.
2. Fix #2 (sequential poll) — straightforward `async`/`awaitAll` change.
3. Fix #3 (sync commit) — small, low-risk.
4. Fix #4 (dead code) — trivial cleanup, do it alongside #1 since it's in
   the same file.

After fixing, re-run `python3 -m pytest` (companion) and
`./gradlew :shared:iosSimulatorArm64Test :androidApp:assembleDebug` (per
README) before considering this done.
