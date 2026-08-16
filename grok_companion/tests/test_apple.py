import time
from pathlib import Path

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from jwt.algorithms import RSAAlgorithm

from grok_companion.apple import verify_apple_identity_token
from grok_companion.security import pairing_url
from grok_companion.server import BridgeState, _load_apple_bind, _save_apple_bind


def _rsa_and_jwk():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public = key.public_key()
    jwk = RSAAlgorithm.to_jwk(public)
    import json

    parsed = json.loads(jwk)
    parsed["kid"] = "test-kid"
    parsed["use"] = "sig"
    parsed["alg"] = "RS256"
    return key, {"keys": [parsed]}


def _token(private, **claims):
    now = int(time.time())
    payload = {
        "iss": "https://appleid.apple.com",
        "aud": "dev.chengguan.mirror",
        "sub": "001234.aabbccddeeff",
        "iat": now,
        "exp": now + 600,
    }
    payload.update(claims)
    return jwt.encode(payload, private, algorithm="RS256", headers={"kid": "test-kid"})


def test_verify_apple_identity_token_ok():
    private, jwks = _rsa_and_jwk()
    token = _token(private)
    sub = verify_apple_identity_token(token, jwks=jwks)
    assert sub == "001234.aabbccddeeff"


def test_verify_rejects_wrong_audience():
    private, jwks = _rsa_and_jwk()
    token = _token(private, aud="evil.app")
    try:
        verify_apple_identity_token(token, jwks=jwks)
        raise AssertionError("expected reject")
    except ValueError:
        pass


def test_verify_rejects_garbage():
    try:
        verify_apple_identity_token("not-a-jwt", jwks={"keys": []})
        raise AssertionError("expected reject")
    except ValueError:
        pass


def test_pairing_url_apple_flag_only():
    url = pairing_url(
        "192.168.1.20",
        8787,
        "01a003db-06b0-7a53-9d42-f263250c7890",
        "abcdefghijklmnopqrstuvwxyz012345",
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        require_apple=True,
    )
    assert "apple=1" in url
    assert "icloud" not in url.lower()
    assert "@" not in url


def test_apple_bind_roundtrip(tmp_path: Path):
    path = tmp_path / "apple_bind.json"
    sid = "01a003db-06b0-7a53-9d42-f263250c7890"
    _save_apple_bind(path, sid, "001234.aabbccddeeff")
    assert _load_apple_bind(path, sid) == "001234.aabbccddeeff"
    assert _load_apple_bind(path, "other-session") is None
    state = BridgeState(
        token="t" * 32,
        session_id=sid,
        session_dir=tmp_path,
        cwd=tmp_path,
        require_apple=True,
        apple_bind_path=path,
    )
    assert state.apple_sub == "001234.aabbccddeeff"
