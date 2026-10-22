"""Verified HTTP downloads with a persistent partial file and terminal progress."""

from __future__ import annotations

import hashlib
from http.client import HTTPException
from pathlib import Path
import re
import sys
import time
from urllib.request import Request, urlopen


def file_digest(path, algorithm):
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


class DownloadProgress:
    def __init__(self, total, offset):
        self.total = total
        self.offset = offset
        self.started = time.monotonic()
        self.last_update = 0.0
        self.terminal = sys.stdout.isatty()

    def update(self, downloaded, force=False):
        now = time.monotonic()
        if not force and now - self.last_update < (0.15 if self.terminal else 2):
            return
        self.last_update = now
        fraction = min(downloaded / self.total, 1)
        filled = int(fraction * 24)
        speed = (downloaded - self.offset) / max(now - self.started, 0.001)
        remaining = max(self.total - downloaded, 0)
        eta = f"{int(remaining / speed)} s" if speed > 0 else "—"
        mib = 1024 * 1024
        line = (
            f"[{'#' * filled}{'-' * (24 - filled)}] {fraction:6.1%}  "
            f"{downloaded / mib:.1f}/{self.total / mib:.1f} MiB  "
            f"{speed / mib:.1f} MiB/s  zbývá {eta}"
        )
        print(
            ("\r\033[K" if self.terminal else "") + line,
            end="" if self.terminal else "\n",
            flush=True,
        )

    def close(self):
        if self.terminal:
            print(flush=True)


def download(
    url: str, destination: Path, size: int, algorithm: str, checksum: str
) -> Path:
    """Resume only validated byte ranges; verify the whole archive before publishing it."""
    if size <= 0:
        raise ValueError("Neplatná velikost stahovaného souboru.")
    checksum = checksum.strip().lower()
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file():
        if (
            destination.stat().st_size == size
            and file_digest(destination, algorithm) == checksum
        ):
            print("Používám již stažený a ověřený archiv.", flush=True)
            return destination
        destination.unlink()
    # A changed upstream archive gets a separate partial file.
    partial = destination.with_name(destination.name + f".{checksum}.part")
    offset = partial.stat().st_size if partial.exists() else 0
    if offset > size:
        partial.write_bytes(b"")
        offset = 0
    if offset < size:
        if offset:
            print(
                f"Navazuji na stažených {offset / (1024 * 1024):.1f} MiB…", flush=True
            )
        headers = {"Accept-Encoding": "identity"}
        if offset:
            headers["Range"] = f"bytes={offset}-"
        try:
            with urlopen(Request(url, headers=headers), timeout=60) as response:
                status = response.getcode()
                if status == 206:
                    match = re.fullmatch(
                        r"bytes (\d+)-(\d+)/(\d+)",
                        response.headers.get("Content-Range", ""),
                    )
                    if (
                        match is None
                        or int(match[1]) != offset
                        or int(match[3]) != size
                        or not offset <= int(match[2]) < size
                    ):
                        raise RuntimeError(
                            "Server vrátil neplatný rozsah stahování; rozpracovaný soubor zůstává zachován."
                        )
                elif status == 200:
                    if offset:
                        print(
                            "Server nepodporuje navázání; stahuji archiv od začátku.",
                            flush=True,
                        )
                    offset = 0
                else:
                    raise RuntimeError(f"Neočekávaná HTTP odpověď: {status}")
                progress = DownloadProgress(size, offset)
                downloaded = offset
                try:
                    with partial.open("ab" if offset else "wb") as output:
                        progress.update(downloaded, force=True)
                        while chunk := response.read(256 * 1024):
                            if downloaded + len(chunk) > size:
                                raise RuntimeError(
                                    "Server poslal více dat, než uvádějí metadata archivu."
                                )
                            output.write(chunk)
                            downloaded += len(chunk)
                            progress.update(downloaded)
                        progress.update(downloaded, force=True)
                finally:
                    progress.close()
                if downloaded != size:
                    raise RuntimeError(
                        "Stahování bylo přerušeno; při příštím --setup naváže na uložená data."
                    )
        except (OSError, HTTPException, KeyboardInterrupt) as error:
            print(
                "\nRozpracované stahování je uložené. Zopakuj příkaz s --setup pro pokračování.",
                file=sys.stderr,
            )
            if isinstance(error, KeyboardInterrupt):
                raise
            raise RuntimeError(f"Stahování bylo přerušeno: {error}") from error
    print("Ověřuji kontrolní součet…", flush=True)
    if file_digest(partial, algorithm) != checksum:
        partial.unlink()
        raise RuntimeError(
            "Kontrolní součet nesouhlasí. Poškozený archiv byl odstraněn; další --setup jej stáhne znovu."
        )
    partial.replace(destination)
    return destination
