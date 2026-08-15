from companion.security import is_tailscale_ipv4, pairing_url, valid_session_id


def test_pairing_url_shape():
    url = pairing_url(
        "192.168.1.20",
        8787,
        "01a003db-06b0-7a53-9d42-f263250c7890",
        "abcdefghijklmnopqrstuvwxyz012345",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
    assert url.startswith("grok-mirror://v1?")
    assert "host=192.168.1.20" in url
    assert "port=8787" in url
    assert "sid=01a003db-06b0-7a53-9d42-f263250c7890" in url
    assert "tok=" in url
    assert "fp=0123456789abcdef" in url
    assert valid_session_id("01a003db-06b0-7a53-9d42-f263250c7890")
    assert is_tailscale_ipv4("100.77.197.23")
    assert not is_tailscale_ipv4("192.168.1.20")
    assert not is_tailscale_ipv4("100.63.0.1")
    assert not is_tailscale_ipv4("8.8.8.8")
