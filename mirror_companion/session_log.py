"""Parse Grok session updates.jsonl into chat turns. Input is untrusted disk JSONL."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

_MONITOR = re.compile(
    r"<monitor-event\b[^>]*>(.*?)</monitor-event>",
    re.DOTALL | re.IGNORECASE,
)
_SYSTEM = re.compile(
    r"<system-reminder\b[^>]*>.*?</system-reminder>",
    re.DOTALL | re.IGNORECASE,
)
_WATCH_PREFIX = re.compile(r"^\[[^\]]*\]\s*")


MAX_LINE = 256 * 1024
MAX_MESSAGES = 2_000
MAX_TEXT = 32_768


def find_session_dir(agent_home: Path, session_id: str) -> Path | None:
    if not _valid_session_id(session_id):
        return None
    root = agent_home / "sessions"
    if not root.is_dir():
        return None
    for path in root.rglob(session_id):
        if path.is_dir() and (path / "updates.jsonl").is_file():
            return path
    return None


def _valid_session_id(value: str) -> bool:
    if len(value) < 8 or len(value) > 80:
        return False
    return all(c.isalnum() or c in "-_" for c in value)


def load_messages(session_dir: Path) -> list[dict[str, Any]]:
    log = session_dir / "updates.jsonl"
    if not log.is_file():
        return []
    user_buf: list[str] = []
    agent_buf: list[str] = []
    out: list[dict[str, Any]] = []

    def flush_user() -> None:
        text = clean_visible_text("".join(user_buf)).strip()
        user_buf.clear()
        if text:
            out.append({"role": "user", "text": text[:MAX_TEXT]})

    def flush_agent() -> None:
        text = clean_visible_text("".join(agent_buf)).strip()
        agent_buf.clear()
        if text:
            out.append({"role": "assistant", "text": text[:MAX_TEXT]})

    with log.open("r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            if len(raw) > MAX_LINE:
                continue
            try:
                event = json.loads(raw)
            except json.JSONDecodeError:
                continue
            update = (
                event.get("params", {}).get("update")
                if isinstance(event, dict)
                else None
            )
            if not isinstance(update, dict):
                continue
            kind = update.get("sessionUpdate")
            content = update.get("content")
            piece = ""
            if isinstance(content, dict) and content.get("type") == "text":
                piece = str(content.get("text") or "")
            if kind == "user_message_chunk":
                if agent_buf:
                    flush_agent()
                user_buf.append(piece)
            elif kind == "agent_message_chunk":
                if user_buf:
                    flush_user()
                agent_buf.append(piece)
            elif kind in {"agent_thought_chunk", "tool_call", "tool_call_update"}:
                continue
    if user_buf:
        flush_user()
    if agent_buf:
        flush_agent()
    return out[-MAX_MESSAGES:]


def clean_visible_text(raw: str) -> str:
    """Drop harness wrappers so phone bubbles show only the spoken line."""
    text = _SYSTEM.sub("", raw)

    def unwrap(match: re.Match[str]) -> str:
        inner = match.group(1).strip()
        inner = _WATCH_PREFIX.sub("", inner)
        if inner.upper().startswith("MIRROR "):
            inner = inner[7:]
        return inner.strip()

    text = _MONITOR.sub(unwrap, text)
    return text.strip()
