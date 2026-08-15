from companion.security import token_matches, valid_message, valid_session_id
from companion.session_log import _valid_session_id


def test_session_id():
    assert valid_session_id("01a003db-06b0-7a53-9d42-f263250c7890")
    assert not valid_session_id("../etc/passwd")
    assert not valid_session_id("x")
    assert _valid_session_id("01a003db-06b0-7a53-9d42-f263250c7890")


def test_token_compare():
    token = "a" * 32
    assert token_matches(token, token)
    assert not token_matches(token, "b" * 32)
    assert not token_matches(token, "")


def test_message_limits():
    assert valid_message("hello")
    assert not valid_message("")
    assert not valid_message("x" * 20_000)
    assert not valid_message("nul\x00byte")
