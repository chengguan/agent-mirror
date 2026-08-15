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
