#!/usr/bin/env python3
"""Local browser editor. No private files are served by the HTTP handler."""

from __future__ import annotations

import hashlib
import json
import os
import runpy
import secrets
import subprocess
import threading
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
ASSETS = Path(__file__).resolve().parent / "editor"
SERVER_VERSION = "1.2.0"


class EditorError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


class Database:
    def __init__(self, root=ROOT):
        self.manager = runpy.run_path(str(ROOT / "scripts" / "script.py"))
        # An alternate root permits isolated verification without touching real data.
        self.private = root / "private" / "data.private.json"
        self.encrypted = root / "data.enc.json"
        self.password = self.manager["load_password"]() if root == ROOT else None
        self.lock = threading.RLock()
        self.repository = Repository(root, self.manager["COMMIT_PATHS"])

    def snapshot(self):
        try:
            private, encrypted = self.private.read_bytes(), self.encrypted.read_bytes()
        except OSError as exc:
            raise EditorError(
                "Chybí lokální databáze. Nejdřív ji nastav pomocí script.py init.", 409
            ) from exc
        revision = hashlib.sha256(private + b"\0" + encrypted).hexdigest()
        return private, encrypted, revision

    def read(self):
        if not self.password:
            raise EditorError("Odemkni databázi jejím heslem.", 401)
        private, encrypted, revision = self.snapshot()
        try:
            data = self.manager["normalize"](json.loads(private))
            decoded = self.manager["decrypt"](json.loads(encrypted), self.password)
        except self.manager["DataError"] as exc:
            raise EditorError(str(exc), 401) from exc
        if data != decoded:
            raise EditorError(
                "Privátní a šifrovaná databáze se liší. Sjednoť je ve správci databáze a potom načti editor znovu; žádná kopie nebyla přepsána.",
                409,
            )
        return data, revision, json.loads(encrypted).get("dbVersion", "neuvedena")

    def validate(self, item):
        if not isinstance(item, dict):
            raise EditorError("Neplatná položka.")
        if item.get("type") not in {"task", "test", "event"}:
            raise EditorError("Vyber úkol, test nebo akci.")
        if not isinstance(item.get("name"), str) or not item["name"].strip():
            raise EditorError("Vyplň název položky.")
        if not isinstance(item.get("date"), str) or not self.manager["parse_date"](
            item["date"]
        ):
            raise EditorError("Vyber platné datum.")
        if "time" in item and (
            not isinstance(item["time"], str)
            or (item["time"] and not self.manager["item_time"](item))
        ):
            raise EditorError("Čas musí mít formát HH:mm (00:00–23:59).")
        if any(not isinstance(item.get(key, ""), str) for key in ("task", "solution")):
            raise EditorError("Popis a řešení musí být text.")
        if not isinstance(item.get("aiReviews", []), list) or any(
            key not in self.manager["AI_REVIEW_NAMES"]
            for key in item.get("aiReviews", [])
        ):
            raise EditorError("Neplatná AI kontrola.")
        if not isinstance(item.get("quizzes", []), list):
            raise EditorError("Neplatný seznam procvičování.")
        quizzes = ([item["quiz"]] if "quiz" in item else []) + item.get("quizzes", [])
        for quiz in quizzes:
            if (
                not isinstance(quiz, dict)
                or not isinstance(quiz.get("questions"), list)
                or not quiz["questions"]
            ):
                raise EditorError(
                    "Každé procvičování musí obsahovat alespoň jednu otázku."
                )
            for number, q in enumerate(quiz["questions"], 1):
                valid = isinstance(q, dict)
                kind = q.get("type") if valid else None

                def text(key):
                    return isinstance(q.get(key), str) and bool(q[key].strip())

                def answers():
                    if "answers" not in q:
                        return text("answer")
                    return (
                        isinstance(q["answers"], list)
                        and bool(q["answers"])
                        and all(isinstance(a, str) and a.strip() for a in q["answers"])
                    )

                if kind in {"choice", "binary"}:
                    options = q.get("options")
                    valid = (
                        text("prompt")
                        and isinstance(options, list)
                        and len(options) >= 2
                        and all(isinstance(a, str) and a.strip() for a in options)
                        and type(q.get("answer")) is int
                        and 0 <= q["answer"] < len(options)
                    )
                elif kind == "text":
                    valid = text("prompt") and answers()
                elif kind == "flashcard":
                    valid = text("prompt") and text("answer")
                elif kind == "fill":
                    valid = text("prompt") and self.manager["valid_fill_question"](q)
                    if valid and q.get("numeric"):
                        valid = all(
                            self.manager["re"].fullmatch(r"[0-9]+", a)
                            for a in q["answers"]
                        )
                elif kind == "punctuation":
                    valid = text("sentence") and answers()
                elif kind == "map":
                    valid = self.manager["valid_map_question"](q)
                else:
                    valid = False
                if not valid:
                    raise EditorError(
                        f"Procvičování „{quiz.get('title') or 'Bez názvu'}“, otázka {number}: doplň platné zadání a odpověď."
                    )
                if "image" in q and not self.manager["valid_question_image"](
                    q["image"]
                ):
                    raise EditorError(
                        f"Otázka {number}: obrázek musí být PNG/JPEG do 512 KB a 2048 × 2048 bodů, popis nejvýše 1000 znaků."
                    )
        image_bytes = sum(
            len(self.manager["base64"].b64decode(q["image"]["data"]))
            for quiz in quizzes
            for q in quiz["questions"]
            if "image" in q
        )
        if image_bytes > self.manager["ITEM_IMAGE_MAX_BYTES"]:
            raise EditorError("Obrázky jedné položky mohou mít dohromady nejvýše 6 MB.")
        return item

    def mutate(self, payload, delete=False):
        with self.lock:
            data, revision, _ = self.read()
            if payload.get("revision") != revision:
                raise EditorError(
                    "Databázi mezitím změnil jiný editor nebo skript. Rozepsaný formulář zůstává zachovaný. Načti aktuální data a změnu zopakuj.",
                    409,
                )
            item_id = payload.get("id")
            tasks = data["tasks"]
            index = next(
                (i for i, item in enumerate(tasks) if item.get("id") == item_id), None
            )
            if item_id is not None and index is None:
                raise EditorError(
                    "Položka už v databázi není. Načti aktuální data.", 409
                )
            if delete:
                if index is None:
                    raise EditorError("Vyber položku ke smazání.")
                tasks.pop(index)
            else:
                incoming = payload.get("item")
                if not isinstance(incoming, dict):
                    raise EditorError("Chybí položka.")
                item = {**(tasks[index] if index is not None else {}), **incoming}
                item["id"] = item_id or str(uuid.uuid4())
                # Missing optional fields explicitly cleared by the editor.
                for key in ("time", "quiz", "quizzes", "aiReviews"):
                    if key not in incoming:
                        item.pop(key, None)
                self.validate(item)
                if index is None:
                    tasks.append(item)
                else:
                    tasks[index] = item
            data = self.manager["normalize"](data)
            tasks.sort(
                key=lambda item: (
                    self.manager["parse_date"](item.get("date", ""))
                    or self.manager["date"].max,
                    self.manager["item_time"](item),
                )
            )
            old_private, old_encrypted, _ = self.snapshot()
            envelope = self.manager["envelope"](
                data, self.password, json.loads(old_encrypted).get("dbVersion")
            )
            if self.snapshot()[2] != revision:
                raise EditorError(
                    "Během ukládání se databáze změnila. Načti aktuální data.", 409
                )
            try:
                self.manager["atomic_write"](self.private, data)
                self.manager["atomic_write"](self.encrypted, envelope)
            except OSError:
                self.manager["atomic_write"](self.private, json.loads(old_private))
                self.manager["atomic_write"](self.encrypted, json.loads(old_encrypted))
                raise
            return self.state(saved_id=None if delete else item["id"])

    def state(self, **extra):
        data, revision, version = self.read()
        return {
            "tasks": data["tasks"],
            "revision": revision,
            "version": version,
            "serverVersion": SERVER_VERSION,
            **extra,
        }


class Repository:
    """Local commits limited to the same project paths as the CLI manager."""

    def __init__(self, root, paths):
        self.root = root.resolve()
        self.paths = paths

    def git(self, *args):
        try:
            result = subprocess.run(
                ["git", "--literal-pathspecs", *args],
                cwd=self.root,
                capture_output=True,
                timeout=60,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise EditorError(
                "Git není dostupný nebo operace trvala příliš dlouho. Zkontroluj stav repozitáře."
            ) from exc
        if result.returncode:
            detail = (
                (result.stderr or result.stdout)
                .decode("utf-8", errors="replace")
                .strip()[:1500]
            )
            raise EditorError(f"Git operace se nezdařila: {detail}")
        return result.stdout

    def preview(self):
        top = self.git("rev-parse", "--show-toplevel").decode().strip()
        if Path(top).resolve() != self.root:
            raise EditorError("Editor musí běžet v kořeni Git repozitáře projektu.")
        head = self.git("rev-parse", "HEAD").decode().strip()
        branch = self.git("branch", "--show-current").decode().strip()
        if not branch:
            raise EditorError("Nejdřív přepni repozitář na větev; HEAD je odpojený.")
        for name in (
            "MERGE_HEAD",
            "CHERRY_PICK_HEAD",
            "REVERT_HEAD",
            "rebase-merge",
            "rebase-apply",
        ):
            path = Path(os.fsdecode(self.git("rev-parse", "--git-path", name)).strip())
            if (self.root / path).exists():
                raise EditorError(
                    "Nejdřív dokonči probíhající merge, rebase nebo cherry-pick v terminálu."
                )
        names = set()
        for args in (
            ("diff", "--no-renames", "--name-only", "-z", "HEAD"),
            ("ls-files", "--others", "--exclude-standard", "-z"),
        ):
            names.update(
                os.fsdecode(name) for name in self.git(*args).split(b"\0") if name
            )
        files = sorted(
            name
            for name in names
            if any(name == path or name.startswith(path + "/") for path in self.paths)
        )
        digest = hashlib.sha256((head + "\0" + branch).encode())
        for name in files:
            path = self.root / name
            digest.update(b"\0" + os.fsencode(name) + b"\0")
            try:
                if path.is_symlink():
                    digest.update(b"link\0" + os.fsencode(os.readlink(path)))
                elif path.is_file():
                    digest.update(b"file\0" + str(path.stat().st_mode).encode() + b"\0")
                    content_hash = hashlib.sha256()
                    with path.open("rb") as source:
                        for chunk in iter(lambda: source.read(1024 * 1024), b""):
                            content_hash.update(chunk)
                    digest.update(content_hash.digest())
                elif not path.exists():
                    digest.update(b"deleted")
                else:
                    raise EditorError(f"Cestu {name} nelze commitnout z editoru.")
            except OSError as exc:
                raise EditorError(
                    "Soubory se změnily nebo je nelze přečíst. Načti nový přehled.", 409
                ) from exc
        return {
            "files": files,
            "excluded": len(names) - len(files),
            "branch": branch,
            "revision": digest.hexdigest(),
        }

    def commit(self, payload):
        message = payload.get("message")
        if (
            not isinstance(message, str)
            or not 1 <= len(message.strip()) <= 2000
            or "\0" in message
        ):
            raise EditorError("Zadej zprávu commitu (1–2000 znaků).")
        preview = self.preview()
        if payload.get("revision") != preview["revision"]:
            raise EditorError(
                "Soubory se od náhledu změnily. Načti nový přehled a commit potvrď znovu.",
                409,
            )
        if not preview["files"]:
            raise EditorError("Žádné projektové změny k commitnutí.")
        files = preview["files"]
        self.git("diff", "--check", "--", *files)
        self.git("add", "-A", "--", *files)
        if self.preview()["revision"] != preview["revision"]:
            raise EditorError(
                "Během přípravy se soubory změnily. Načti nový přehled; změny zůstávají lokálně.",
                409,
            )
        # --only prevents unrelated pre-staged files from entering this commit.
        self.git("commit", "--only", "-m", message.strip(), "--", *files)
        return {
            "commit": self.git("rev-parse", "--short", "HEAD").decode().strip(),
            "branch": preview["branch"],
            "files": files,
        }


class EditorHandler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, status, content, mime="application/json; charset=utf-8"):
        if not isinstance(content, bytes):
            content = {**content, "serverVersion": SERVER_VERSION}
            content = json.dumps(content, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        )
        self.end_headers()
        self.wfile.write(content)

    def route(self, post=False):
        try:
            host = self.headers.get("Host", "")
            if host != f"127.0.0.1:{self.server.server_port}":
                raise EditorError("Použij adresu serveru 127.0.0.1.", 403)
            path = urlsplit(self.path).path
            if path.startswith("/api/"):
                if not secrets.compare_digest(
                    self.headers.get("X-Editor-Token", ""), self.server.token
                ):
                    raise EditorError("Otevři odkaz vypsaný při spuštění serveru.", 403)
                if self.headers.get("Origin") not in {None, f"http://{host}"}:
                    raise EditorError("Požadavek z jiné stránky není povolený.", 403)
                with self.server.database.lock:
                    if not post and path == "/api/state":
                        return self.send(200, self.server.database.state())
                    if not post and path == "/api/git":
                        self.server.database.read()
                        return self.send(200, self.server.database.repository.preview())
                    if post:
                        if (
                            self.headers.get("Content-Type", "").split(";")[0]
                            != "application/json"
                        ):
                            raise EditorError("Očekáván JSON.", 415)
                        size = int(self.headers.get("Content-Length", "0"))
                        if size <= 0 or size > 12 * 1024 * 1024:
                            raise EditorError(
                                "Požadavek je příliš velký nebo prázdný.", 413
                            )
                        payload = json.loads(self.rfile.read(size))
                        if not isinstance(payload, dict):
                            raise EditorError("Neplatný požadavek.")
                        if path == "/api/unlock":
                            password = payload.get("password")
                            if not isinstance(password, str) or not password:
                                raise EditorError("Zadej heslo.")
                            previous = self.server.database.password
                            self.server.database.password = password
                            try:
                                state = self.server.database.state()
                            except Exception:
                                self.server.database.password = previous
                                raise
                            return self.send(200, state)
                        if path == "/api/commit":
                            self.server.database.read()
                            return self.send(
                                200, self.server.database.repository.commit(payload)
                            )
                        if path in {"/api/items", "/api/delete"}:
                            return self.send(
                                200,
                                self.server.database.mutate(
                                    payload, delete=path == "/api/delete"
                                ),
                            )
                raise EditorError("Neznámý požadavek.", 404)
            files = {
                "/": (ASSETS / "index.html", "text/html; charset=utf-8"),
                "/editor.css": (ASSETS / "editor.css", "text/css; charset=utf-8"),
                "/editor.js": (ASSETS / "editor.js", "text/javascript; charset=utf-8"),
                "/js/quiz-map.js": (
                    ROOT / "web/js/quiz-map.js",
                    "text/javascript; charset=utf-8",
                ),
                "/js/quiz-image.js": (
                    ROOT / "web/js/quiz-image.js",
                    "text/javascript; charset=utf-8",
                ),
                "/css/quiz-image.css": (
                    ROOT / "web/css/quiz-image.css",
                    "text/css; charset=utf-8",
                ),
                "/css/quiz-map.css": (
                    ROOT / "web/css/quiz-map.css",
                    "text/css; charset=utf-8",
                ),
                "/maps/world.json": (
                    ROOT / "web/maps/world.json",
                    "application/json; charset=utf-8",
                ),
                "/marked.js": (
                    ROOT / "web/vendor/marked-18.1.1.js",
                    "text/javascript; charset=utf-8",
                ),
                "/purify.js": (
                    ROOT / "web/vendor/dompurify-3.4.17.min.js",
                    "text/javascript; charset=utf-8",
                ),
            }
            if post or path not in files:
                raise EditorError("Stránka neexistuje.", 404)
            file, mime = files[path]
            self.send(200, file.read_bytes(), mime)
        except EditorError as exc:
            self.send(exc.status, {"error": str(exc)})
        except (ValueError, TypeError):
            self.send(400, {"error": "Neplatný formát požadavku."})
        except Exception:
            self.send(
                500,
                {
                    "error": "Operace se nezdařila. Zkontroluj lokální soubory a přístupová práva; formulář zůstává zachovaný."
                },
            )

    def do_GET(self):
        self.route()

    def do_POST(self):
        self.route(post=True)


def serve(port=8765):
    database = Database()
    try:
        server = ThreadingHTTPServer(("127.0.0.1", port), EditorHandler)
    except OSError as exc:
        raise database.manager["DataError"](
            f"Port {port} není dostupný. Použij například server 8766."
        ) from exc
    server.token = secrets.token_urlsafe(32)
    server.database = database
    url = f"http://127.0.0.1:{server.server_port}/#{server.token}"
    print(f"\n  Lokální editor · server {SERVER_VERSION}")
    print(f"  {url}")
    print(
        "  Otevírám editor v prohlížeči. Ctrl+C ukončí server. Uložení mění pouze lokální databázi.\n",
        flush=True,
    )

    def open_browser():
        try:
            opened = webbrowser.open(url, new=2)
        except (webbrowser.Error, OSError):
            opened = False
        if not opened:
            print(
                "  Prohlížeč se nepodařilo otevřít. Použij odkaz uvedený výše.",
                flush=True,
            )

    try:
        threading.Thread(target=open_browser, daemon=True).start()
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nServer ukončen.")
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
