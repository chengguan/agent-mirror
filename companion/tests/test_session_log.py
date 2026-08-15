import json
from pathlib import Path

from companion.session_log import load_messages


def test_load_chunks(tmp_path: Path):
    log = tmp_path / "updates.jsonl"
    events = [
        {
            "params": {
                "update": {
                    "sessionUpdate": "user_message_chunk",
                    "content": {"type": "text", "text": "Hi"},
                }
            }
        },
        {
            "params": {
                "update": {
                    "sessionUpdate": "agent_message_chunk",
                    "content": {"type": "text", "text": "Hello"},
                }
            }
        },
        {
            "params": {
                "update": {
                    "sessionUpdate": "agent_thought_chunk",
                    "content": {"type": "text", "text": "secret thought"},
                }
            }
        },
    ]
    log.write_text("\n".join(json.dumps(e) for e in events) + "\n")
    messages = load_messages(tmp_path)
    assert messages == [
        {"role": "user", "text": "Hi"},
        {"role": "assistant", "text": "Hello"},
    ]


def test_strips_monitor_event_tags(tmp_path: Path):
    log = tmp_path / "updates.jsonl"
    wrapped = (
        '<monitor-event task_id="abc">\n'
        "[Watch Mirror inbox for phone sends] MIRROR Hello from phone\n"
        "</monitor-event>"
    )
    log.write_text(
        json.dumps({
            "params": {
                "update": {
                    "sessionUpdate": "user_message_chunk",
                    "content": {"type": "text", "text": wrapped},
                }
            }
        })
        + "\n"
    )
    messages = load_messages(tmp_path)
    assert messages == [{"role": "user", "text": "Hello from phone"}]


def test_drops_system_reminder_only(tmp_path: Path):
    log = tmp_path / "updates.jsonl"
    log.write_text(
        json.dumps({
            "params": {
                "update": {
                    "sessionUpdate": "user_message_chunk",
                    "content": {
                        "type": "text",
                        "text": '<system-reminder>\nBackground task done\n</system-reminder>',
                    },
                }
            }
        })
        + "\n"
    )
    assert load_messages(tmp_path) == []
