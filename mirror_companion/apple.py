"""Verify Sign in with Apple identity tokens (Apple's JWT + JWKS).

Do not invent crypto. Do not log the token or the Apple `sub`.
"""

from __future__ import annotations

import json
import ssl
import time
import urllib.request
from typing import Any, Callable

APPLE_ISS = "https://appleid.apple.com"
APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"
DEFAULT_AUDIENCE = "dev.chengguan.mirror"
MAX_TOKEN_LEN = 8_192

_jwks_cache: tuple[float, dict[str, Any]] | None = None
JWKS_TTL_SEC = 3600


def fetch_apple_jwks() -> dict[str, Any]:
    global _jwks_cache
    now = time.time()
    if _jwks_cache and now - _jwks_cache[0] < JWKS_TTL_SEC:
        return _jwks_cache[1]
    req = urllib.request.Request(
        APPLE_JWKS_URL,
        headers={"Accept": "application/json", "User-Agent": "mirror-companion/1.0"},
    )
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("keys"), list):
        raise ValueError("bad jwks")
    _jwks_cache = (now, data)
    return data


def verify_apple_identity_token(
    token: str,
    *,
    audience: str = DEFAULT_AUDIENCE,
    jwks: dict[str, Any] | None = None,
    jwks_fetcher: Callable[[], dict[str, Any]] = fetch_apple_jwks,
) -> str:
    """Return Apple `sub` if the identity token is valid. Raise ValueError otherwise."""
    import jwt
    from jwt import PyJWKClientError
    from jwt.exceptions import InvalidTokenError

    raw = (token or "").strip()
    if not raw or len(raw) > MAX_TOKEN_LEN or raw.count(".") != 2:
        raise ValueError("bad token")
    keys = jwks if jwks is not None else jwks_fetcher()
    try:
        header = jwt.get_unverified_header(raw)
    except InvalidTokenError as exc:
        raise ValueError("bad token") from exc
    kid = header.get("kid")
    if not kid or header.get("alg") != "RS256":
        raise ValueError("bad token")
    key = _jwk_for_kid(keys, str(kid))
    try:
        claims = jwt.decode(
            raw,
            key=key,
            algorithms=["RS256"],
            audience=audience,
            issuer=APPLE_ISS,
            options={"require": ["exp", "iat", "sub", "iss", "aud"]},
        )
    except (InvalidTokenError, PyJWKClientError, ValueError) as exc:
        raise ValueError("bad token") from exc
    sub = str(claims.get("sub") or "").strip()
    if len(sub) < 8 or len(sub) > 128:
        raise ValueError("bad token")
    return sub


def _jwk_for_kid(jwks: dict[str, Any], kid: str) -> Any:
    from jwt.algorithms import RSAAlgorithm

    for item in jwks.get("keys") or []:
        if isinstance(item, dict) and item.get("kid") == kid:
            return RSAAlgorithm.from_jwk(json.dumps(item))
    raise ValueError("unknown kid")
