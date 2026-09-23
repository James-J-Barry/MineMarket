#!/usr/bin/env python3
"""Minimal Minecraft RCON client (stdlib only).

    python3 scripts/rcon.py "mkt book DIAMOND"
    python3 scripts/rcon.py --port 25575 --password dev "list"

Prints the server's reply and exits non-zero if it cannot connect or authenticate.
Note: RCON runs commands as the server console, so player-only commands such as
`/mkt buy` need a player; use `execute as <player> run ...` or a GameTest instead.
"""
import argparse
import socket
import struct
import sys

AUTH, EXEC = 3, 2


def packet(req_id: int, kind: int, body: str) -> bytes:
    payload = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read_packet(sock: socket.socket):
    raw_len = _recv_exact(sock, 4)
    (length,) = struct.unpack("<i", raw_len)
    data = _recv_exact(sock, length)
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, kind, data[8:-2].decode("utf-8", errors="replace")


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--password", default="dev")
    ap.add_argument("command", nargs="+")
    args = ap.parse_args()

    try:
        with socket.create_connection((args.host, args.port), timeout=10) as s:
            s.sendall(packet(1, AUTH, args.password))
            req_id, _, _ = read_packet(s)
            if req_id == -1:
                print("RCON auth failed", file=sys.stderr)
                return 2
            s.sendall(packet(2, EXEC, " ".join(args.command)))
            _, _, body = read_packet(s)
            print(body)
            return 0
    except (OSError, ConnectionError) as e:
        print(f"RCON error: {e} (is the dev server running? ./scripts/dev.sh server start)", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
