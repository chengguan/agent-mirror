from pathlib import Path

from companion.inbox import append_inbox, drain_inbox, load_inbox, tui_owns_session
from companion.server import BridgeState, _json_bytes, _visible_messages


def test_inbox_roundtrip(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("GROK_HOME", str(tmp_path))
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    append_inbox(tmp_path, sid, "It works")
    assert load_inbox(tmp_path, sid) == [{"role": "user", "text": "It works"}]


def test_visible_includes_inbox(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("GROK_HOME", str(tmp_path))
    log = tmp_path / "sess"
    log.mkdir()
    (log / "updates.jsonl").write_text("")
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    append_inbox(tmp_path, sid, "from phone")
    state = BridgeState(token="t" * 32, session_id=sid, session_dir=log, cwd=tmp_path)
    messages = _visible_messages(state)
    assert messages[-1] == {"role": "user", "text": "from phone"}


def test_drain_inbox_removes_file(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("GROK_HOME", str(tmp_path))
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    append_inbox(tmp_path, sid, "queued once")
    assert drain_inbox(tmp_path, sid) == [{"role": "user", "text": "queued once"}]
    assert load_inbox(tmp_path, sid) == []


def test_tui_owns_ignores_resume(monkeypatch):
    import subprocess as sp

    class Fake:
        stdout = (
            "grok --resume 01a003db --single hi --output-format plain\n"
            "/Users/chengguan/.grok/bin/grok --resume x --cwd /tmp --single y\n"
            "python3 -m companion pair --session 01a003db\n"
        )
        returncode = 0

    monkeypatch.setattr(sp, "run", lambda *a, **k: Fake())
    assert tui_owns_session() is False


def test_tui_owns_detects_interactive(monkeypatch):
    import subprocess as sp

    class Fake:
        stdout = "grok\n/Users/chengguan/.grok/bin/grok\n"
        returncode = 0

    monkeypatch.setattr(sp, "run", lambda *a, **k: Fake())
    assert tui_owns_session() is True


def test_json_bytes_keeps_unicode_apostrophe():
    raw = _json_bytes({"text": "don\u2019t"})
    decoded = raw.decode("utf-8")
    assert "\\u2019" not in decoded
    assert "don\u2019t" in decoded
