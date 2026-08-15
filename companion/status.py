"""Derive a live TUI status from the session log (no secrets)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from companion.billing import load_billing
from companion.inbox import load_inbox
from companion.inbox import tui_owns_session

TAIL_BYTES = 128 * 1024


def live_status(session_dir: Path, grok_home: Path, session_id: str) -> dict[str, Any]:
    summary = _read_json(session_dir / "summary.json")
    last = _last_update(session_dir / "updates.jsonl")
    inbox_n = len(load_inbox(grok_home, session_id))
    phase, detail = classify(last, inbox_n, summary)
    usage = usage_from_signals(session_dir / "signals.json", summary)
    billing = load_billing(grok_home)
    # usage_percent is account /usage credit when billing is present.
    # Context-window tokens stay on tokens_* / context_percent.
    payload = {
        "phase": phase,
        "detail": detail[:240],
        "model": str(summary.get("current_model_id") or usage.get("model") or ""),
        "inbox": inbox_n,
        "tui": tui_owns_session(),
        **usage,
        "context_percent": usage.get("usage_percent", 0),
        # Do not let session-context % stand in for /usage billing.
        "usage_percent": 0,
        "billing": False,
    }
    if billing:
        payload.update(billing)
    return payload


def usage_from_signals(path: Path, summary: dict[str, Any]) -> dict[str, Any]:
    signals = _read_json(path)
    window = int(signals.get("contextWindowTokens") or 0)
    used = int(signals.get("contextTokensUsed") or 0)
    percent = int(signals.get("contextWindowUsage") or 0)
    if window > 0 and used >= 0 and not percent:
        percent = min(100, int(round(100.0 * used / window)))
    return {
        "usage_percent": max(0, min(100, percent)),
        "context_percent": max(0, min(100, percent)),
        "tokens_used": max(0, used),
        "tokens_window": max(0, window),
        "turns": int(signals.get("turnCount") or 0),
        "compactions": int(signals.get("compactionCount") or 0),
        "tool_calls": int(signals.get("toolCallCount") or 0),
        "duration_seconds": int(signals.get("sessionDurationSeconds") or 0),
        "tokens_before_compaction": int(signals.get("totalTokensBeforeCompaction") or 0),
        "usage_model": str(signals.get("primaryModelId") or summary.get("current_model_id") or ""),
    }


def classify(
    last: dict[str, Any] | None,
    inbox_n: int,
    summary: dict[str, Any],
) -> tuple[str, str]:
    kind = last.get("sessionUpdate") if last else None
    title = str((last or {}).get("title") or "").strip()
    tool_status = str((last or {}).get("status") or "")
    last_summary = str(summary.get("last_turn_summary") or "").strip()
    if kind == "agent_thought_chunk":
        return "thinking", "Thinking"
    if kind == "tool_call":
        return "working", title or "Using a tool"
    if kind == "tool_call_update":
        return "working", title or ("Working" if tool_status == "completed" else "Using a tool")
    if inbox_n:
        return "queued", f"{inbox_n} phone message(s) waiting"
    if kind == "agent_message_chunk":
        return "idle", last_summary or "Idle"
    return "idle", last_summary or "Idle"


def _last_update(log: Path) -> dict[str, Any] | None:
    if not log.is_file():
        return None
    size = log.stat().st_size
    with log.open("rb") as handle:
        handle.seek(max(0, size - TAIL_BYTES))
        data = handle.read().decode("utf-8", errors="replace")
    last: dict[str, Any] | None = None
    for raw in data.splitlines():
        if not raw.strip():
            continue
        try:
            event = json.loads(raw)
        except json.JSONDecodeError:
            continue
        update = event.get("params", {}).get("update") if isinstance(event, dict) else None
        if isinstance(update, dict) and update.get("sessionUpdate"):
            last = update
    return last


def _read_json(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}
