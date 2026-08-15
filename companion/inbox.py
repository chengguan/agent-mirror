"""Queue phone turns when the Mac TUI already owns the session."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from companion.security import valid_session_id


def inbox_path(grok_home: Path, session_id: str) -> Path:
    return grok_home / "mirror" / "inbox" / f"{session_id}.jsonl"


def append_inbox(grok_home: Path, session_id: str, text: str) -> None:
    if not valid_session_id(session_id):
        return
    path = inbox_path(grok_home, session_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"role": "user", "text": text}, ensure_ascii=False) + "\n")


def load_inbox(grok_home: Path, session_id: str) -> list[dict[str, Any]]:
    if not valid_session_id(session_id):
        return []
    path = inbox_path(grok_home, session_id)
    if not path.is_file():
        return []
    out: list[dict[str, Any]] = []
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not raw.strip():
            continue
        try:
            item = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if not isinstance(item, dict):
            continue
        text = str(item.get("text") or "").strip()
        if text:
            out.append({"role": "user", "text": text})
    return out


def drain_inbox(grok_home: Path, session_id: str) -> list[dict[str, Any]]:
    """Read queued phone turns and remove the file so they are not applied twice."""
    items = load_inbox(grok_home, session_id)
    path = inbox_path(grok_home, session_id)
    if path.is_file():
        path.unlink()
    return items


def tui_owns_session() -> bool:
    """True when the interactive `grok` TUI is already running."""
    import subprocess

    try:
        result = subprocess.run(
            ["pgrep", "-x", "grok"],
            capture_output=True,
            check=False,
        )
    except OSError:
        return False
    return result.returncode == 0
