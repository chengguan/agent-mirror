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


TAILSCALE_CGNAT = ipaddress.ip_network("100.64.0.0/10")


def is_tailscale_ipv4(host: str) -> bool:
    try:
        parsed = ipaddress.ip_address(host)
    except ValueError:
        return False
    return parsed.version == 4 and parsed in TAILSCALE_CGNAT


def tailscale_ipv4() -> str | None:
    import shutil

    binary = shutil.which("tailscale") or "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
    try:
        result = subprocess.run(
            [binary, "ip", "-4"],
            capture_output=True,
            text=True,
            timeout=3,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    for line in result.stdout.splitlines():
        candidate = line.strip()
        if is_tailscale_ipv4(candidate):
            return candidate
    return None


def tailscale_dns_name() -> str | None:
    import json
    import shutil

    binary = shutil.which("tailscale") or "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
    try:
        result = subprocess.run(
            [binary, "status", "--json"],
            capture_output=True,
            text=True,
            timeout=4,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0 or not result.stdout.strip():
        return None
    try:
        payload = json.loads(result.stdout)
    except json.JSONDecodeError:
        return None
    name = str((payload.get("Self") or {}).get("DNSName") or "").strip().rstrip(".").lower()
    if name.endswith(".ts.net") and name.count(".") >= 3:
        return name
    return None


def lan_ip() -> str:
    import socket

    magic = tailscale_dns_name()
    if magic:
        return magic
    overlay = tailscale_ipv4()
    if overlay:
        return overlay
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


def pairing_url(
    host: str,
    port: int,
    session_id: str,
    token: str,
    fingerprint: str,
    *,
    require_apple: bool = False,
) -> str:
    from urllib.parse import urlencode

    query: dict[str, str] = {
        "host": host,
        "port": str(port),
        "sid": session_id,
        "tok": token,
        "fp": fingerprint,
    }
    if require_apple:
        # Flag only — never put an Apple ID or email in the QR.
        query["apple"] = "1"
    return f"grok-mirror://v1?{urlencode(query)}"


def companion_hostname() -> str:
    """Short Mac name for the phone tab. Never a secret."""
    import socket

    name = (socket.gethostname() or "").strip()
    if name.endswith(".local"):
        name = name[: -len(".local")]
    name = name.split(".")[0].strip()
    return name[:40] or "Mac"


def choose_listen_port(preferred: int, bind: str = "0.0.0.0"):
    """Bind and keep the socket so the chosen port cannot be stolen."""
    import socket

    ports = [preferred] if preferred != 8787 else list(range(8787, 8820))
    host = "" if bind in {"0.0.0.0", "::"} else bind
    last_error: OSError | None = None
    for port in ports:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            sock.bind((host, port))
            return port, sock
        except OSError as exc:
            last_error = exc
            sock.close()
    raise OSError(str(last_error or "no free Mirror port"))
