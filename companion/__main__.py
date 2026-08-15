"""CLI: python3 -m companion pair --session <id>"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from companion.security import (
    ensure_tls,
    lan_ip,
    new_token,
    pairing_url,
    valid_session_id,
)
from companion.server import BridgeState, serve
from companion.session_log import find_session_dir


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="grok-mirror")
    sub = parser.add_subparsers(dest="cmd", required=True)
    pair = sub.add_parser("pair", help="Start the LAN bridge and print a QR")
    pair.add_argument("--session", required=True, help="Grok session id")
    pair.add_argument("--cwd", default=str(Path.cwd()), help="Workspace for grok --resume")
    pair.add_argument("--port", type=int, default=8787)
    pair.add_argument("--grok-home", default=str(Path.home() / ".grok"))
    pair.add_argument("--bind", default="0.0.0.0", help="Same-LAN only; do not expose to the internet")
    args = parser.parse_args(argv)

    if args.cmd != "pair":
        return 2
    if not valid_session_id(args.session):
        print("invalid session id", file=sys.stderr)
        return 2
    grok_home = Path(args.grok_home)
    session_dir = find_session_dir(grok_home, args.session)
    if session_dir is None:
        print("session not found under ~/.grok/sessions", file=sys.stderr)
        return 1

    tls_dir = grok_home / "mirror" / "tls"
    cert, key, fingerprint = ensure_tls(tls_dir)
    token = new_token()
    host = lan_ip()
    url = pairing_url(host, args.port, args.session, token, fingerprint)

    def out(msg: str = "") -> None:
        print(msg, flush=True)

    out()
    out("Grok Mirror pairing (same Wi-Fi only)")
    out("Scan this QR with the Mirror app, or the Camera app if the app is installed.")
    out()
    try:
        import segno

        qr = segno.make(url, error="m")
        qr.terminal(compact=True)
        sys.stdout.flush()
        png = grok_home / "mirror" / "pair.png"
        png.parent.mkdir(parents=True, exist_ok=True)
        qr.save(str(png), scale=6)
        out(f"QR image: {png}")
    except Exception:
        out("(install segno for a terminal QR: pip install --user segno)")
    out()
    out("Pairing URL (contains a secret — do not commit or screenshot publicly):")
    out(url)
    out()
    out(f"Session: {args.session}")
    out(f"LAN:     https://{host}:{args.port}")
    out(f"Pin:     sha256:{fingerprint}")
    out("Ctrl+C to stop. Leave this process running while you use the phone.")
    out()

    state = BridgeState(
        token=token,
        session_id=args.session,
        session_dir=session_dir,
        cwd=Path(args.cwd).resolve(),
    )
    httpd = serve(state, cert, key, args.bind, args.port)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
