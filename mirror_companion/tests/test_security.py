from mirror_companion.security import (
    choose_listen_port,
    companion_hostname,
    token_matches,
    valid_message,
    valid_session_id,
)
from mirror_companion.session_log import _valid_session_id


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


def test_companion_hostname_is_short():
    name = companion_hostname()
    assert name
    assert len(name) <= 40
    assert "\n" not in name and "\r" not in name


def test_choose_listen_port_finds_free():
    port, sock = choose_listen_port(8787)
    try:
        assert 8787 <= port < 8820
    finally:
        sock.close()
    import socket

    busy = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    busy.bind(("", 0))
    taken = busy.getsockname()[1]
    try:
        raised = False
        try:
            choose_listen_port(taken)
        except OSError:
            raised = True
        assert raised
    finally:
        busy.close()


def test_message_limits():
    assert valid_message("hello")
    assert not valid_message("")
    assert not valid_message("x" * 20_000)
    assert not valid_message("nul\x00byte")
