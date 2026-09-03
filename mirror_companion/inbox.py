"""Queue phone turns when the Mac TUI already owns the session."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mirror_companion.security import valid_session_id


def inbox_path(agent_home: Path, session_id: str) -> Path:
    return agent_home / "mirror" / "inbox" / f"{session_id}.jsonl"


def append_inbox(agent_home: Path, session_id: str, text: str) -> None:
    if not valid_session_id(session_id):
        return
    existing = load_inbox(agent_home, session_id)
    if existing and existing[-1].get("text") == text:
        # Double tap / retried POST — do not queue the same line twice.
        return
    path = inbox_path(agent_home, session_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps({"role": "user", "text": text}, ensure_ascii=False) + "\n")


def load_inbox(agent_home: Path, session_id: str) -> list[dict[str, Any]]:
    if not valid_session_id(session_id):
        return []
    path = inbox_path(agent_home, session_id)
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


def drain_inbox(agent_home: Path, session_id: str) -> list[dict[str, Any]]:
    """Read queued phone turns and remove the file so they are not applied twice."""
    items = load_inbox(agent_home, session_id)
    path = inbox_path(agent_home, session_id)
    if path.is_file():
        path.unlink()
    return items


def tui_owns_session() -> bool:
    """True when the interactive TUI is running (not a hung `grok --resume`)."""
    import subprocess

    try:
        result = subprocess.run(
            ["ps", "-ax", "-o", "command="],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    for raw in result.stdout.splitlines():
        line = raw.strip()
        # "grok" here is the real OS process/binary name of the agent CLI
        # this companion pairs with today — not a label we control.
        if "grok" not in line:
            continue
        if "--resume" in line or "--single" in line:
            continue
        if "companion" in line or "pytest" in line:
            continue
        name = line.split()[0].rsplit("/", 1)[-1]
        if name.startswith("grok"):
            return True
    return False
