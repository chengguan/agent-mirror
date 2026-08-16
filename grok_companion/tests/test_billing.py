import json
from pathlib import Path

from grok_companion.billing import load_billing


def test_load_billing_from_log(tmp_path: Path):
    assert load_billing(tmp_path) == {}
    log = tmp_path / "logs" / "unified.jsonl"
    log.parent.mkdir()
    log.write_text(
        json.dumps({"msg": "other"})
        + "\n"
        + json.dumps({
            "msg": "billing: fetched credits config",
            "ctx": {
                "config": {
                    "creditUsagePercent": 1.0,
                    "currentPeriod": {"type": "USAGE_PERIOD_TYPE_MONTHLY", "end": "2026-09-01"},
                },
                "subscriptionTier": "old",
            },
        })
        + "\n"
        + json.dumps({
            "msg": "billing: fetched credits config",
            "ctx": {
                "config": {
                    "creditUsagePercent": 55.4,
                    "currentPeriod": {
                        "type": "USAGE_PERIOD_TYPE_WEEKLY",
                        "start": "2026-08-13T16:53:48.325233+00:00",
                        "end": "2026-08-20T16:53:48.325233+00:00",
                    },
                    "onDemandUsed": {"val": 2},
                    "onDemandCap": {"val": 10},
                    "prepaidBalance": {"val": 0},
                },
                "subscriptionTier": "X Premium+",
            },
        })
        + "\n"
    )
    billing = load_billing(tmp_path)
    assert billing["usage_percent"] == 55
    assert billing["billing"] is True
    assert billing["billing_kind"] == "weekly"
    assert billing["billing_start"] == "2026-08-13"
    assert billing["billing_resets"] == "2026-08-20"
    assert billing["subscription_tier"] == "X Premium+"
    assert billing["on_demand_used"] == 2
    assert billing["on_demand_cap"] == 10


def test_load_billing_finds_snapshot_past_small_tail(tmp_path: Path):
    log = tmp_path / "logs" / "unified.jsonl"
    log.parent.mkdir()
    event = json.dumps({
        "msg": "billing: fetched credits config",
        "ctx": {
            "config": {
                "creditUsagePercent": 65.0,
                "currentPeriod": {"type": "USAGE_PERIOD_TYPE_WEEKLY", "end": "2026-08-20"},
            },
            "subscriptionTier": "X Premium+",
        },
    })
    padding = ("{" + ("x" * 200) + "}\n") * 2000  # well over the old 256 KiB tail
    log.write_text(event + "\n" + padding)
    billing = load_billing(tmp_path)
    assert billing["usage_percent"] == 65
    assert billing["billing"] is True
    assert billing["subscription_tier"] == "X Premium+"


def test_load_billing_ignores_corrupt_lines(tmp_path: Path):
    log = tmp_path / "logs" / "unified.jsonl"
    log.parent.mkdir()
    log.write_text("{not json\nbilling: fetched credits config\n")
    assert load_billing(tmp_path) == {}
