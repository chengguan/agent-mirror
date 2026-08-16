"""Account /usage credit — the TUI billing bar, not session context tokens.

Grok writes the live credit snapshot to ~/.grok/logs/unified.jsonl after it
fetches billing. We read that (no auth.json, no token in our process) and
forward only numbers and period labels to the phone.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

# unified.jsonl is busy; billing events are infrequent. Search far enough
# back that a quiet overnight does not hide the last /usage snapshot.
TAIL_BYTES = 4 * 1024 * 1024
BILLING_MSG = "billing: fetched credits config"


def load_billing(grok_home: Path) -> dict[str, Any]:
    ctx = _latest_credits_config(grok_home / "logs" / "unified.jsonl")
    if not ctx:
        return {}
    config = ctx.get("config") if isinstance(ctx.get("config"), dict) else {}
    percent = _as_percent(config.get("creditUsagePercent"))
    period = config.get("currentPeriod") if isinstance(config.get("currentPeriod"), dict) else {}
    kind = _period_kind(period.get("type"))
    end = _iso_date(period.get("end") or config.get("billingPeriodEnd"))
    start = _iso_date(period.get("start") or config.get("billingPeriodStart"))
    tier = str(ctx.get("subscriptionTier") or "").strip()[:40]
    if percent is None and not kind:
        return {}
    return {
        "usage_percent": percent if percent is not None else 0,
        "billing": True,
        "billing_kind": kind,
        "billing_start": start,
        "billing_resets": end,
        "subscription_tier": tier,
        "on_demand_used": _money_val(config.get("onDemandUsed")),
        "on_demand_cap": _money_val(config.get("onDemandCap")),
        "prepaid_balance": _money_val(config.get("prepaidBalance")),
    }


def _latest_credits_config(path: Path) -> dict[str, Any] | None:
    if not path.is_file():
        return None
    size = path.stat().st_size
    with path.open("rb") as handle:
        handle.seek(max(0, size - TAIL_BYTES))
        data = handle.read().decode("utf-8", errors="replace")
    last: dict[str, Any] | None = None
    for raw in data.splitlines():
        if BILLING_MSG not in raw:
            continue
        try:
            event = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if event.get("msg") != BILLING_MSG:
            continue
        ctx = event.get("ctx")
        if isinstance(ctx, dict):
            last = ctx
    return last


def _as_percent(value: Any) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return max(0, min(100, int(round(float(value)))))
    if isinstance(value, str):
        try:
            return max(0, min(100, int(round(float(value)))))
        except ValueError:
            return None
    return None


def _period_kind(raw: Any) -> str:
    text = str(raw or "").upper()
    if "WEEK" in text:
        return "weekly"
    if "MONTH" in text:
        return "monthly"
    return ""


def _iso_date(raw: Any) -> str:
    text = str(raw or "").strip()
    if len(text) >= 10 and text[4] == "-" and text[7] == "-":
        return text[:10]
    return ""


def _money_val(raw: Any) -> int:
    if isinstance(raw, dict) and "val" in raw:
        raw = raw.get("val")
    if isinstance(raw, bool) or raw is None:
        return 0
    if isinstance(raw, (int, float)):
        return max(0, int(raw))
    return 0
