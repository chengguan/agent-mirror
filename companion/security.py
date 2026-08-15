"""Pairing secrets and TLS material. Tokens never go to logs."""

from __future__ import annotations

import hashlib
import ipaddress
import re
import secrets
import subprocess
from pathlib import Path

TOKEN_BYTES = 32
MAX_MESSAGE = 16_384
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,80}$")


def new_token() -> str:
    return secrets.token_urlsafe(TOKEN_BYTES)


def token_matches(expected: str, provided: str) -> bool:
    if not expected or not provided:
        return False
    if len(expected) != len(provided):
        return False
    return secrets.compare_digest(expected.encode("utf-8"), provided.encode("utf-8"))


def valid_session_id(value: str) -> bool:
    return bool(SESSION_ID_RE.fullmatch(value))


def valid_message(text: str) -> bool:
    if not text or len(text) > MAX_MESSAGE:
        return False
    if "\x00" in text:
        return False
    return True


def lan_ip() -> str:
    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.0.2.1", 80))
        ip = sock.getsockname()[0]
    except OSError:
        ip = "127.0.0.1"
    finally:
        sock.close()
    try:
        parsed = ipaddress.ip_address(ip)
    except ValueError:
        return "127.0.0.1"
    if parsed.is_loopback or parsed.is_unspecified:
        return "127.0.0.1"
    return ip


def ensure_tls(tls_dir: Path) -> tuple[Path, Path, str]:
    """Create a local RSA cert with openssl (platform TLS, not homemade crypto)."""
    tls_dir.mkdir(parents=True, exist_ok=True)
    cert = tls_dir / "cert.pem"
    key = tls_dir / "key.pem"
    if not cert.is_file() or not key.is_file():
        subprocess.run(
            [
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-sha256",
                "-days",
                "365",
                "-nodes",
                "-keyout",
                str(key),
                "-out",
                str(cert),
                "-subj",
                "/CN=grok-mirror.local",
            ],
            check=True,
            capture_output=True,
        )
        key.chmod(0o600)
        cert.chmod(0o600)
    fingerprint = cert_sha256(cert)
    return cert, key, fingerprint


def cert_sha256(cert: Path) -> str:
    pem = cert.read_bytes()
    # Extract DER between PEM fences, then SHA-256 (OWASP M5 pinning).
    body = b"".join(
        line
        for line in pem.splitlines()
        if line and not line.startswith(b"-----")
    )
    import base64

    der = base64.b64decode(body)
    return hashlib.sha256(der).hexdigest()


def pairing_url(host: str, port: int, session_id: str, token: str, fingerprint: str) -> str:
    from urllib.parse import urlencode

    query = urlencode(
        {
            "host": host,
            "port": str(port),
            "sid": session_id,
            "tok": token,
            "fp": fingerprint,
        }
    )
    return f"grok-mirror://v1?{query}"
