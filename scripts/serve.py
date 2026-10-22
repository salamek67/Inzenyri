#!/usr/bin/env python3
"""Serve the public web tree and encrypted database for local development."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class PublicHandler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        if path.split("?", 1)[0] == "/data.enc.json":
            return str(ROOT / "data.enc.json")
        return super().translate_path(path)


if __name__ == "__main__":
    server = ThreadingHTTPServer(
        ("127.0.0.1", 8000), partial(PublicHandler, directory=str(ROOT / "web"))
    )
    print("Web: http://127.0.0.1:8000 (Ctrl+C ukončí server)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
