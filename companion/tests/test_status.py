import json
from pathlib import Path

from companion.status import classify, live_status


def test_classify_working_and_idle():
    phase, detail = classify({"sessionUpdate": "tool_call", "title": "read_file"}, 0, {})
    assert phase == "working"
    assert "read_file" in detail
    phase, detail = classify({"sessionUpdate": "agent_thought_chunk"}, 0, {})
    assert phase == "thinking"
    phase, detail = classify(
        {"sessionUpdate": "agent_message_chunk"},
        0,
        {"last_turn_summary": "Paired and idle"},
    )
    assert phase == "idle"
    assert "idle" in detail.lower() or "Paired" in detail
    phase, detail = classify({"sessionUpdate": "agent_message_chunk"}, 2, {})
    assert phase == "queued"
    assert "2" in detail


def test_live_status_from_log(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("GROK_HOME", str(tmp_path))
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    sess = tmp_path / "sess"
    sess.mkdir()
    (sess / "summary.json").write_text(json.dumps({"current_model_id": "grok-4.6"}))
    (sess / "updates.jsonl").write_text(
        json.dumps({
            "params": {
                "update": {
                    "sessionUpdate": "tool_call",
                    "title": "run_terminal_command",
                }
            }
        })
        + "\n"
    )
    (sess / "signals.json").write_text(
        json.dumps({
            "contextWindowUsage": 52,
            "contextTokensUsed": 262235,
            "contextWindowTokens": 500000,
            "turnCount": 78,
            "compactionCount": 4,
            "toolCallCount": 1017,
            "sessionDurationSeconds": 22652,
            "totalTokensBeforeCompaction": 1599726,
            "primaryModelId": "grok-4.6",
        })
    )
    status = live_status(sess, tmp_path, sid)
    assert status["phase"] == "working"
    assert status["model"] == "grok-4.6"
    assert status["inbox"] == 0
    assert status["usage_percent"] == 52
    assert status["tokens_used"] == 262235
    assert status["tokens_window"] == 500000
