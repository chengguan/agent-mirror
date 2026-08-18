# Mirror v3.0 — multiple sessions

Product decisions (16 Aug 2026):

- Sessions may be several Grok TUIs on one Mac, or Macs on different machines.
- Pairing screen: a tab per saved session plus **Add**.
- Chat screen: the same tabs sit **under the message box**.
- Tab title is editable. Default is the companion Mac hostname.
- Background refresh is **on** by default; Settings can turn it off.
- Unpair drops that pairing, then asks whether to save the conversation on the phone.

Lock still covers the whole app. Tokens stay in Keychain / Keystore. Archives are
local, read-only, and do not keep the pairing secret.

One companion process still serves one session. A second pair on the same Mac
takes the next free port from 8787.
