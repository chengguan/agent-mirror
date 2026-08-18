"""Same-LAN HTTPS bridge. Auth is a pairing token; cert is pinned by the phone."""

from __future__ import annotations

import json
import os
import ssl
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from grok_companion.apple import DEFAULT_AUDIENCE, verify_apple_identity_token
from grok_companion.inbox import append_inbox, load_inbox, tui_owns_session
from grok_companion.security import MAX_MESSAGE, token_matches, valid_message
from grok_companion.session_log import load_messages
from grok_companion.status import live_status

GROK_BIN = os.environ.get("GROK_BIN", str(Path.home() / ".grok/bin/grok"))


def grok_home() -> Path:
    return Path(os.environ.get("GROK_HOME", str(Path.home() / ".grok")))


class BridgeState:
    def __init__(
        self,
        *,
        token: str,
        session_id: str,
        session_dir: Path,
        cwd: Path,
        require_apple: bool = False,
        apple_audience: str = DEFAULT_AUDIENCE,
        apple_bind_path: Path | None = None,
    ) -> None:
        self.token = token
        self.session_id = session_id
        self.session_dir = session_dir
        self.cwd = cwd
        self.require_apple = require_apple
        self.apple_audience = apple_audience
        self.apple_bind_path = apple_bind_path
        self.apple_sub: str | None = _load_apple_bind(apple_bind_path, session_id) if require_apple else None
        self.lock = threading.Lock()
        self.busy = False


def make_handler(state: BridgeState):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt: str, *args: Any) -> None:
            # M6: do not log Authorization or bodies.
            sys_stderr = __import__("sys").stderr
            sys_stderr.write("%s %s\n" % (self.command, self.path.split("?", 1)[0]))

        def _unauthorized(self) -> None:
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":"unauthorized"}')

        def _bad(self, code: int, msg: str) -> None:
            raw = _json_bytes({"error": msg})
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _ok(self, payload: Any) -> None:
            raw = _json_bytes(payload)
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _authorized(self) -> bool:
            header = self.headers.get("Authorization", "")
            if not header.startswith("Bearer "):
                return False
            return token_matches(state.token, header[7:].strip())

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/v1/health":
                # Unauthenticated liveness only — do not leak the session id.
                self._ok({"ok": True})
                return
            if not self._authorized():
                self._unauthorized()
                return
            if not self._apple_ready():
                self._bad(403, "apple required")
                return
            if path == "/v1/messages":
                self._ok(_snapshot(state))
                return
            if path == "/v1/status":
                self._ok({"status": _status(state)})
                return
            self._bad(404, "not found")

        def _apple_ready(self) -> bool:
            return (not state.require_apple) or bool(state.apple_sub)

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            sys_stderr = __import__("sys").stderr
            sys_stderr.write("%s %s\n" % (self.command, path))
            sys_stderr.flush()
            if not self._authorized():
                self._unauthorized()
                return
            if path == "/v1/apple":
                self._bind_apple()
                return
            if not self._apple_ready():
                self._bad(403, "apple required")
                return
            if path != "/v1/message":
                self._bad(404, "not found")
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 0 or length > MAX_MESSAGE + 512:
                self._bad(413, "too large")
                return
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._bad(400, "invalid json")
                return
            text = str(body.get("text") or "")
            if not valid_message(text):
                self._bad(400, "invalid message")
                return
            # A live TUI already owns this session; grok --resume would hang
            # and the phone Send button would stay disabled.
            if tui_owns_session():
                append_inbox(grok_home(), state.session_id, text)
                # Do not echo the full thread — parsing it on Send crashed iOS.
                self._ok({"ok": True, "queued": True, "status": _status(state)})
                return
            with state.lock:
                if state.busy:
                    self._bad(409, "busy")
                    return
                state.busy = True
            try:
                result = subprocess.run(
                    [
                        GROK_BIN,
                        "--resume",
                        state.session_id,
                        "--cwd",
                        str(state.cwd),
                        "--single",
                        text,
                        "--output-format",
                        "plain",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=300,
                )
            except (OSError, subprocess.TimeoutExpired):
                with state.lock:
                    state.busy = False
                self._bad(502, "grok failed")
                return
            with state.lock:
                state.busy = False
            if result.returncode != 0:
                self._bad(502, "grok failed")
                return
            payload = _snapshot(state)
            payload["ok"] = True
            self._ok(payload)

        def _bind_apple(self) -> None:
            if not state.require_apple:
                self._bad(400, "apple not required")
                return
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 8_192 + 64:
                self._bad(413, "too large")
                return
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except json.JSONDecodeError:
                self._bad(400, "invalid json")
                return
            identity = str(body.get("identity_token") or "")
            try:
                sub = verify_apple_identity_token(identity, audience=state.apple_audience)
            except ValueError:
                self._bad(403, "apple identity rejected")
                return
            with state.lock:
                if state.apple_sub and state.apple_sub != sub:
                    self._bad(403, "apple identity mismatch")
                    return
                state.apple_sub = sub
                _save_apple_bind(state.apple_bind_path, state.session_id, sub)
            self._ok({"ok": True, "apple": True})

    return Handler


def _load_apple_bind(path: Path | None, session_id: str) -> str | None:
    if path is None:
        return None
    loaded = _read_apple_bind_file(path, session_id)
    if loaded:
        return loaded
    legacy = path.parent / "apple_bind.json"
    if legacy == path or not legacy.is_file():
        return None
    loaded = _read_apple_bind_file(legacy, session_id)
    if loaded:
        _save_apple_bind(path, session_id, loaded)
    return loaded


def _read_apple_bind_file(path: Path, session_id: str) -> str | None:
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict):
        return None
    if data.get("sid") != session_id:
        return None
    sub = str(data.get("sub") or "").strip()
    return sub if 8 <= len(sub) <= 128 else None


def _save_apple_bind(path: Path | None, session_id: str, sub: str) -> None:
    if path is None:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"sid": session_id, "sub": sub}), encoding="utf-8")
    path.chmod(0o600)


def _json_bytes(payload: Any) -> bytes:
    # Keep apostrophes and other Unicode as UTF-8. ascii dumps turn ’ into
    # \u2019, which the first phone parser showed as literal text.
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def _visible_messages(state: BridgeState) -> list:
    return _merge_inbox(
        load_messages(state.session_dir),
        load_inbox(grok_home(), state.session_id),
    )


def _merge_inbox(messages: list, inbox: list) -> list:
    """Inbox is the live phone turn. Once the TUI has written that same user
    line into the session log, do not append it again (duplicate bubbles)."""
    if not inbox:
        return messages
    out = list(messages)
    for item in inbox:
        last = out[-1] if out else None
        if (
            last
            and last.get("role") == "user"
            and last.get("text") == item.get("text")
        ):
            continue
        out.append(item)
    return out


def _status(state: BridgeState) -> dict:
    return live_status(state.session_dir, grok_home(), state.session_id)


def _snapshot(state: BridgeState) -> dict:
    return {"messages": _visible_messages(state), "status": _status(state)}


def serve(
    state: BridgeState,
    cert: Path,
    key: Path,
    host: str,
    port: int,
    listen_sock=None,
) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((host, port), make_handler(state), bind_and_activate=False)
    if listen_sock is not None:
        httpd.socket = listen_sock
    else:
        httpd.server_bind()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(str(cert), str(key))
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    httpd.server_activate()
    return httpd
