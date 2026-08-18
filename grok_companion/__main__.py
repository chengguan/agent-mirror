"""CLI: python3 -m grok_companion pair --session <id>"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from grok_companion.security import (
    choose_listen_port,
    companion_hostname,
    ensure_tls,
    lan_ip,
    new_token,
    pairing_url,
    valid_session_id,
)
from grok_companion.server import BridgeState, serve
from grok_companion.session_log import find_session_dir
from grok_companion.watch import watch_inbox


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="grok-companion")
    sub = parser.add_subparsers(dest="cmd", required=True)
    pair = sub.add_parser("pair", help="Start the LAN bridge and print a QR")
    pair.add_argument("--session", required=True, help="Grok session id")
    pair.add_argument("--cwd", default=str(Path.cwd()), help="Workspace for grok --resume")
    pair.add_argument("--port", type=int, default=8787)
    pair.add_argument("--grok-home", default=str(Path.home() / ".grok"))
    pair.add_argument("--bind", default="0.0.0.0", help="Same-LAN only; do not expose to the internet")
    pair.add_argument(
        "--token-file",
        help="Reuse a pairing token from a 0600 file (restart without a new QR)",
    )
    pair.add_argument("--quiet", action="store_true", help="Do not print the pairing URL")
    pair.add_argument(
        "--require-apple",
        action="store_true",
        help="iPhone-only: require Sign in with Apple. Android is rejected. QR has no Apple ID.",
    )
    watch = sub.add_parser("watch", help="Print new inbox lines for the live TUI")
    watch.add_argument("--session", required=True, help="Grok session id")
    watch.add_argument("--grok-home", default=str(Path.home() / ".grok"))
    watch.add_argument("--interval", type=float, default=0.25)
    args = parser.parse_args(argv)

    if args.cmd == "watch":
        if not valid_session_id(args.session):
            print("invalid session id", file=sys.stderr)
            return 2
        watch_inbox(Path(args.grok_home), args.session, interval=args.interval)
        return 0
    if args.cmd != "pair":
        return 2
    if not valid_session_id(args.session):
        print("invalid session id", file=sys.stderr)
        return 2
    grok_home = Path(args.grok_home)
    os.environ["GROK_HOME"] = str(grok_home)
    session_dir = find_session_dir(grok_home, args.session)
    if session_dir is None:
        print("session not found under ~/.grok/sessions", file=sys.stderr)
        return 1

    tls_dir = grok_home / "mirror" / "tls"
    cert, key, fingerprint = ensure_tls(tls_dir)
    token = _read_token_file(args.token_file) if args.token_file else new_token()
    if not token:
        print("invalid token file", file=sys.stderr)
        return 2
    try:
        port, listen_sock = choose_listen_port(args.port, args.bind)
    except OSError:
        print("no free port for the companion", file=sys.stderr)
        return 1
    host = lan_ip()
    url = pairing_url(
        host,
        port,
        args.session,
        token,
        fingerprint,
        require_apple=args.require_apple,
    )

    def out(msg: str = "") -> None:
        print(msg, flush=True)

    out()
    out("Grok Mirror pairing — v3.0 - multiple sessions")
    if args.require_apple:
        out("Apple ID required — iPhone only. Android cannot pair. The QR does not contain your Apple ID.")
    if args.quiet:
        out("Companion listening. Pairing URL not printed.")
    else:
        out("Scan this QR with the Mirror app, or tap Scan QR in the app.")
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
    out(f"Host:    {companion_hostname()}")
    out(f"Reach:   https://{host}:{port}")
    out(f"Pin:     sha256:{fingerprint}")
    out("Ctrl+C to stop. Leave this process running while you use the phone.")
    out()

    state = BridgeState(
        token=token,
        session_id=args.session,
        session_dir=session_dir,
        cwd=Path(args.cwd).resolve(),
        require_apple=args.require_apple,
        apple_bind_path=(
            grok_home / "mirror" / f"apple_bind.{args.session}.json" if args.require_apple else None
        ),
    )
    httpd = serve(state, cert, key, args.bind, port, listen_sock=listen_sock)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        httpd.server_close()
    return 0


def _read_token_file(path: str) -> str | None:
    raw = Path(path).read_text(encoding="utf-8").strip()
    if 16 <= len(raw) <= 128 and "\n" not in raw and " " not in raw:
        return raw
    return None


if __name__ == "__main__":
    raise SystemExit(main())
