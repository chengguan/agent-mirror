from pathlib import Path

from companion.inbox import append_inbox, drain_inbox
from companion.watch import InboxTail, format_event, parse_inbox_line


def test_parse_and_format():
    assert parse_inbox_line('{"role":"user","text":"Can you see me?"}') == "Can you see me?"
    assert parse_inbox_line("not json") == ""
    assert format_event("line1\nline2") == "MIRROR line1 line2"


def test_tail_emits_only_new_complete_lines(tmp_path: Path):
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    tail = InboxTail()
    inbox = tmp_path / "mirror" / "inbox" / f"{sid}.jsonl"
    assert tail.pull(inbox) == []
    append_inbox(tmp_path, sid, "Can you see me?")
    append_inbox(tmp_path, sid, "Testing?")
    assert tail.pull(inbox) == ["Can you see me?", "Testing?"]
    assert tail.pull(inbox) == []
    append_inbox(tmp_path, sid, "Test again")
    assert tail.pull(inbox) == ["Test again"]


def test_tail_resets_after_drain(tmp_path: Path):
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    tail = InboxTail()
    append_inbox(tmp_path, sid, "first")
    inbox = tmp_path / "mirror" / "inbox" / f"{sid}.jsonl"
    assert tail.pull(inbox) == ["first"]
    drain_inbox(tmp_path, sid)
    assert tail.pull(inbox) == []
    append_inbox(tmp_path, sid, "second")
    assert tail.pull(inbox) == ["second"]
