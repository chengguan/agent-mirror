"""Tail the phone inbox and emit new lines for the live TUI monitor."""

from __future__ import annotations

import json
import time
from pathlib import Path

from mirror_companion.inbox import inbox_path
from mirror_companion.security import valid_session_id


class InboxTail:
    """Follow a jsonl inbox without draining it. Truncate/unlink resets."""

    def __init__(self) -> None:
        self.pos = 0
        self.buf = ""

    def pull(self, path: Path) -> list[str]:
        if not path.is_file():
            self.pos = 0
            self.buf = ""
            return []
        size = path.stat().st_size
        if self.pos > size:
            self.pos = 0
            self.buf = ""
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            handle.seek(self.pos)
            chunk = handle.read()
            self.pos = handle.tell()
        self.buf += chunk
        out: list[str] = []
        while "\n" in self.buf:
            raw, self.buf = self.buf.split("\n", 1)
            text = parse_inbox_line(raw)
            if text:
                out.append(text)
        return out


def parse_inbox_line(raw: str) -> str:
    if not raw.strip():
        return ""
    try:
        item = json.loads(raw)
    except json.JSONDecodeError:
        return ""
    if not isinstance(item, dict):
        return ""
    return str(item.get("text") or "").strip()


def format_event(text: str) -> str:
    one_line = " ".join(text.splitlines()).strip()
    return f"MIRROR {one_line}"


def watch_inbox(agent_home: Path, session_id: str, *, interval: float = 0.25) -> None:
    if not valid_session_id(session_id):
        raise SystemExit(2)
    path = inbox_path(agent_home, session_id)
    tail = InboxTail()
    while True:
        for text in tail.pull(path):
            print(format_event(text), flush=True)
        time.sleep(max(0.05, interval))
