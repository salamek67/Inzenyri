#!/usr/bin/env python3
from __future__ import annotations
import base64, getpass, hashlib, json, os, shlex, subprocess, sys, tempfile
import re
from datetime import date, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ENC = ROOT / "data.enc.json"
PRIVATE = ROOT / "data.private.json"
PASSWORD_FILE = ROOT / ".inzenyri-password"
FORMAT = "inzenyri-encrypted-data"
VERSION = 1
ITERATIONS = 600_000
AAD = b"inzenyri-data:v1"
TYPES = {"task": "Úkol", "test": "Test", "event": "Akce"}
COMMANDS = (
    "add",
    "list",
    "delete",
    "encrypt",
    "init",
    "password",
    "commit",
    "help",
    "clear",
    "quit",
)
SESSION_PASSWORD = None


class DataError(Exception):
    pass


COLOR = sys.stdout.isatty() and not os.environ.get("NO_COLOR")


def style(text, code):
    return f"\033[{code}m{text}\033[0m" if COLOR else str(text)


def accent(text):
    return style(text, "38;5;141")


def muted(text):
    return style(text, "38;5;245")


def bold(text):
    return style(text, "1")


def success(message):
    print(f"\n  {style('✓','32')} {message}")


def warning(message):
    print(f"\n  {style('•','33')} {message}")


def secret(prompt):
    return getpass.getpass(f"  {accent('›')} {prompt}: ")


def heading(title, subtitle=None):
    print(f"\n{accent('╭─')} {bold(title)}")
    if subtitle:
        print(f"{accent('│')} {muted(subtitle)}")
    print(accent("╰" + "─" * 54))


def command(key, name, description):
    print(f"  {accent(f'{key:>12}')}  {bold(f'{name:<20}')} {muted(description)}")


def logo():
    print(accent("\n  ╭──────────────────────────────────────────────╮"))
    print(
        f"  {accent('│')}  {bold('INŽENÝŘI')}  {muted('· bezpečná správa přehledu')}          {accent('│')}"
    )
    print(accent("  ╰──────────────────────────────────────────────╯"))
    state = (
        style("● data.enc.json připraven", "32")
        if ENC.exists()
        else style("○ data.enc.json chybí", "33")
    )
    print(f"  {state}   {muted('Tab = doplnit · help = nápověda')}\n")


def cli_error(message):
    print(
        f"\n  {style('✕','31')} {style('Chyba','1;31')}: {message}\n", file=sys.stderr
    )


def aesgcm():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        return AESGCM
    except ImportError as exc:
        raise DataError(
            "Chybí cryptography. Spusť: python3 -m pip install -r requirements.txt"
        ) from exc


def parse_date(value):
    for fmt in ("%d.%m.%Y", "%d.%m.%y", "%Y-%m-%d"):
        try:
            return datetime.strptime(str(value).strip(), fmt).date()
        except ValueError:
            pass
    return None


def normalize(value):
    if (
        not isinstance(value, dict)
        or not isinstance(value.get("tasks"), list)
        or not all(isinstance(x, dict) for x in value["tasks"])
    ):
        raise DataError("Data musí být JSON objekt s polem objektů 'tasks'.")
    for item in value["tasks"]:
        quiz = item.get("quiz")
        if not isinstance(quiz, dict):
            continue
        questions = quiz.get("questions", [])
        if not isinstance(questions, list):
            raise DataError("Pole quiz.questions musí být seznam.")
        for question in questions:
            if isinstance(question, dict) and question.get("type") == "binary":
                question["type"] = "choice"
    return {"tasks": value["tasks"]}


def b64(value):
    return base64.b64encode(value).decode("ascii")


def unb64(value, label):
    try:
        if not isinstance(value, str):
            raise ValueError
        return base64.b64decode(value, validate=True)
    except (ValueError, TypeError) as exc:
        raise DataError(f"Neplatné Base64 pole {label}.") from exc


def key(password, salt, iterations):
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations, 32)


def next_db_version():
    prefix = datetime.now().strftime("%y.%m.%d")
    number = 0
    if ENC.exists():
        try:
            value = read(ENC)
            previous = value.get("dbVersion", "") if isinstance(value, dict) else ""
            match = re.fullmatch(r"(\d{2}\.\d{2}\.\d{2})-(\d+)", previous)
            if match and match.group(1) == prefix:
                number = int(match.group(2))
        except DataError:
            pass
    return f"{prefix}-{number+1}"


def current_db_version():
    if ENC.exists():
        try:
            value = read(ENC)
            if isinstance(value, dict):
                return str(value.get("dbVersion") or "neuvedena")
        except DataError:
            pass
    return "neuvedena"


def envelope(data, password, db_version=None):
    salt, iv = os.urandom(16), os.urandom(12)
    raw = json.dumps(
        normalize(data), ensure_ascii=False, separators=(",", ":")
    ).encode()
    ciphertext = aesgcm()(key(password, salt, ITERATIONS)).encrypt(iv, raw, AAD)
    return {
        "format": FORMAT,
        "version": VERSION,
        "dbVersion": db_version or current_db_version(),
        "kdf": {
            "name": "PBKDF2",
            "hash": "SHA-256",
            "iterations": ITERATIONS,
            "salt": b64(salt),
        },
        "cipher": {
            "name": "AES-GCM",
            "keyLength": 256,
            "tagLength": 128,
            "iv": b64(iv),
        },
        "ciphertext": b64(ciphertext),
    }


def decrypt(value, password):
    if (
        not isinstance(value, dict)
        or value.get("format") != FORMAT
        or value.get("version") != VERSION
    ):
        raise DataError("Nepodporovaný formát šifrovaného souboru.")
    k, c = value.get("kdf"), value.get("cipher")
    if (
        not isinstance(k, dict)
        or k.get("name") != "PBKDF2"
        or k.get("hash") != "SHA-256"
    ):
        raise DataError("Nepodporované KDF.")
    if (
        not isinstance(c, dict)
        or c.get("name") != "AES-GCM"
        or c.get("tagLength") != 128
    ):
        raise DataError("Nepodporovaná šifra.")
    iterations = k.get("iterations")
    salt, iv, ct = (
        unb64(k.get("salt"), "salt"),
        unb64(c.get("iv"), "iv"),
        unb64(value.get("ciphertext"), "ciphertext"),
    )
    if (
        not isinstance(iterations, int)
        or iterations < 100_000
        or len(salt) < 16
        or len(iv) != 12
        or len(ct) < 16
    ):
        raise DataError("Neplatné parametry šifrovaného souboru.")
    try:
        return normalize(
            json.loads(
                aesgcm()(key(password, salt, iterations))
                .decrypt(iv, ct, AAD)
                .decode("utf-8")
            )
        )
    except Exception as exc:
        raise DataError("Nesprávné heslo nebo poškozený šifrovaný soubor.") from exc


def read(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise DataError(f"Soubor {path.name} neexistuje.") from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise DataError(f"Soubor {path.name} nelze načíst.") from exc


def atomic_write(path, value):
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as f:
            tmp = f.name
            json.dump(value, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
        tmp = None
    finally:
        if tmp:
            Path(tmp).unlink(missing_ok=True)


def save_password(password):
    tmp = None
    try:
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=ROOT,
            prefix=".inzenyri-password.",
            suffix=".tmp",
            delete=False,
        ) as f:
            tmp = f.name
            os.chmod(tmp, 0o600)
            f.write(password)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, PASSWORD_FILE)
        os.chmod(PASSWORD_FILE, 0o600)
        tmp = None
    finally:
        if tmp:
            Path(tmp).unlink(missing_ok=True)


def load_password():
    try:
        password = PASSWORD_FILE.read_text(encoding="utf-8")
        return password if password else None
    except FileNotFoundError:
        return None
    except OSError as exc:
        raise DataError("Lokální soubor s heslem nelze bezpečně načíst.") from exc


def new_password():
    heading(
        "Nastavení hesla",
        "Heslo se nezobrazí; uloží se jen do ignorovaného lokálního souboru.",
    )
    a = secret("Nové heslo")
    b = secret("Nové heslo znovu")
    if a != b:
        raise DataError("Hesla se neshodují.")
    return a


def initialize_private():
    global SESSION_PASSWORD
    SESSION_PASSWORD = new_password()
    atomic_write(ENC, envelope(normalize(read(PRIVATE)), SESSION_PASSWORD))
    save_password(SESSION_PASSWORD)
    success("Vytvořen data.enc.json a lokálně uloženo heslo.")


def encrypt_private():
    global SESSION_PASSWORD
    password = SESSION_PASSWORD or load_password()
    if not password:
        raise DataError("Chybí uložené heslo. Pro první nastavení spusť příkaz init.")
    if ENC.exists():
        decrypt(read(ENC), password)
    data = normalize(read(PRIVATE))
    atomic_write(ENC, envelope(data, password))
    SESSION_PASSWORD = password
    success("Data byla zašifrována uloženým heslem.")


def change_password():
    global SESSION_PASSWORD
    heading("Změna hesla", "Nejdřív ověříme současné heslo.")
    data, _ = open_data()
    SESSION_PASSWORD = new_password()
    atomic_write(ENC, envelope(data, SESSION_PASSWORD))
    save_password(SESSION_PASSWORD)
    success("Heslo bylo bezpečně změněno a lokálně uloženo.")


def open_data():
    global SESSION_PASSWORD
    encrypted = read(ENC)
    if SESSION_PASSWORD is None:
        SESSION_PASSWORD = load_password()
    if SESSION_PASSWORD is not None:
        try:
            return decrypt(encrypted, SESSION_PASSWORD), SESSION_PASSWORD
        except DataError:
            SESSION_PASSWORD = None
            if PASSWORD_FILE.exists():
                PASSWORD_FILE.unlink()
    password = secret("Heslo k datům")
    data = decrypt(encrypted, password)
    SESSION_PASSWORD = password
    save_password(password)
    success("Data odemčena a heslo lokálně uloženo.")
    return data, password


def save(data, password, db_version=None):
    data["tasks"].sort(key=lambda x: parse_date(x.get("date", "")) or date.max)
    atomic_write(ENC, envelope(data, password, db_version))


def ask(prompt):
    return input(f"  {accent('›')} {prompt}: ").strip()


def choose_type():
    return {
        "u": "task",
        "ú": "task",
        "ukol": "task",
        "úkol": "task",
        "t": "test",
        "test": "test",
        "a": "event",
        "akce": "event",
    }.get(ask("Typ [u]kol / [t]est / [a]kce").lower())


def ask_number(prompt, minimum=1, maximum=None):
    try:
        value = int(ask(prompt))
    except ValueError as exc:
        raise DataError("Zadej celé číslo.") from exc
    if value < minimum or maximum is not None and value > maximum:
        raise DataError("Číslo je mimo povolený rozsah.")
    return value


def build_quiz():
    if ask("Přidat interaktivní zkoušení? [a/n]").lower() not in {"a", "ano"}:
        return None
    count = ask_number("Počet otázek", 1, 100)
    questions = []
    for number in range(1, count + 1):
        heading(f"Otázka {number} z {count}", "Výběr z možností nebo textová odpověď")
        kind = {
            "v": "choice",
            "vyber": "choice",
            "výběr": "choice",
            "choice": "choice",
            "t": "text",
            "text": "text",
        }.get(ask("Typ [v]ýběr / [t]ext").lower())
        if not kind:
            raise DataError("Neplatný typ otázky.")
        prompt = ask("Znění otázky")
        if kind == "choice":
            option_count = ask_number("Počet možností", 2, 4)
            options = [ask(f"Možnost {index}") for index in range(1, option_count + 1)]
            if any(not option for option in options):
                raise DataError("Všechny možnosti musí být vyplněné.")
            answer = (
                ask_number(f"Správná možnost [1-{option_count}]", 1, option_count) - 1
            )
            question = {
                "type": "choice",
                "prompt": prompt,
                "options": options,
                "answer": answer,
            }
        else:
            answers = [
                value.strip()
                for value in ask("Správné odpovědi (odděl |)").split("|")
                if value.strip()
            ]
            if not answers:
                raise DataError("Je potřeba alespoň jedna správná odpověď.")
            question = {"type": "text", "prompt": prompt, "answers": answers}
        explanation = ask("Vysvětlení při chybě (volitelné)")
        if explanation:
            question["explanation"] = explanation
        questions.append(question)
    return {"questions": questions}


def add():
    heading("Nová položka", "Úkol, test nebo školní akce")
    data, password = open_data()
    kind = choose_type()
    if not kind:
        raise DataError("Neplatný typ.")
    name, when, text = ask("Název"), ask("Datum (dd.mm.yyyy)"), ask("Popis")
    if not parse_date(when):
        raise DataError("Neplatné datum.")
    solution = ask("Řešení v Markdownu (volitelné)") if kind != "event" else ""
    item = {
        "type": kind,
        "name": name,
        "date": when,
        "task": text,
        "solution": solution,
    }
    if kind == "test":
        quiz = build_quiz()
        if quiz:
            item["quiz"] = quiz
    data["tasks"].append(item)
    save(data, password)
    success("Položka byla zašifrována a uložena.")


def current(data):
    return [
        x
        for x in data["tasks"]
        if not parse_date(x.get("date", ""))
        or parse_date(x.get("date", "")) >= date.today()
    ]


def display(items):
    if not items:
        warning("Žádné aktuální položky.")
        return
    print(
        f"\n  {muted('#'):>3}  {muted('TYP'):<10} {muted('DATUM'):<12} {muted('NÁZEV')}"
    )
    print("  " + muted("─" * 62))
    for i, x in enumerate(items):
        print(
            f"  {accent(str(i)):>3}  {TYPES.get(x.get('type','task'),'Úkol'):<10} {x.get('date',''):<12} {bold(x.get('name',''))}"
        )


def listing():
    heading("Aktuální přehled", "Dešifrovaný pouze pro tento proces")
    data, _ = open_data()
    display(current(data))


def parse_delete_indices(arguments, item_count):
    raw = " ".join(arguments).strip()
    if not raw:
        raw = ask("Indexy ke smazání (např. 1 3 nebo 2-4)")

    tokens = [token for token in re.split(r"[\s,]+", raw) if token]
    if not tokens:
        raise DataError("Zadej alespoň jeden index.")

    indices = []
    for token in tokens:
        interval = re.fullmatch(r"(\d+)-(\d+)", token)
        if interval:
            start, end = map(int, interval.groups())
            if start > end:
                raise DataError(f"Neplatný rozsah „{token}“.")
            values = range(start, end + 1)
        elif token.isdigit():
            values = (int(token),)
        else:
            raise DataError(f"Neplatný index nebo rozsah „{token}“.")

        for index in values:
            if index >= item_count:
                raise DataError(f"Index {index} neexistuje.")
            if index not in indices:
                indices.append(index)
    return indices


def delete(arguments=()):
    data, password = open_data()
    items = current(data)
    display(items)
    if not items:
        return

    indices = parse_delete_indices(arguments, len(items))
    targets = [items[index] for index in indices]
    names = ", ".join(f"„{item.get('name', '')}“" for item in targets)
    count_label = "1 položku" if len(targets) == 1 else f"{len(targets)} položek"
    if ask(f"Smazat {count_label} ({names})? [a/n]").lower() not in {
        "a",
        "ano",
    }:
        print("Zrušeno.")
        return

    target_ids = {id(target) for target in targets}
    data["tasks"] = [item for item in data["tasks"] if id(item) not in target_ids]
    save(data, password)
    success(f"Smazáno položek: {len(targets)}.")


def auto_purge():
    if not ENC.exists():
        return
    data, password = open_data()
    before = len(data["tasks"])
    data["tasks"] = current(data)
    removed = before - len(data["tasks"])
    if removed:
        save(data, password)
        success(f"Automaticky odstraněno {removed} prošlých položek.")
    else:
        print(f"  {style('✓','32')} {muted('Žádné prošlé položky.')}\n")


def commit(message=None):
    if not ENC.exists():
        raise DataError("Nejdřív vytvoř data.enc.json příkazem encrypt.")
    changed = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.splitlines()
    web_files = (
        "index.html",
        "app.js",
        "data.enc.json",
        ".github/workflows/static.yml",
    )
    changed_paths = []
    for line in changed:
        path = line[3:].split(" -> ")[-1].strip('"')
        changed_paths.append(path)
    touches_web = any(path in web_files or path == ".github/" for path in changed_paths)
    data, password = open_data()
    if touches_web:
        save(data, password, next_db_version())
        success("Data ověřena, verzována a znovu zašifrována před publikací.")
    else:
        print(
            f"  {style('✓','32')} {muted('Šifrovaná data ověřena; web se nemění.')}\n"
        )
    files = [
        "index.html",
        "app.js",
        "data.enc.json",
        "script.py",
        "requirements.txt",
        ".gitignore",
        ".github/workflows/static.yml",
    ]
    message = message or ask("Zpráva commitu") or "Aktualizace přehledu"
    if not touches_web and not message.lower().startswith("[ci skip]"):
        message = f"[ci skip] {message}"
    subprocess.run(["git", "add", *files], cwd=ROOT, check=True)
    subprocess.run(["git", "commit", "-m", message], cwd=ROOT, check=True)
    subprocess.run(["git", "push", "-u", "origin", "main"], cwd=ROOT, check=True)


def help_text():
    heading("Příkazy", "Příkaz můžeš napsat celý nebo použít krátkou zkratku.")
    command("add", "Přidat", "nový úkol, test nebo akci")
    command("list", "Přehled", "vypsat aktuální položky")
    command("delete [id…]", "Smazat", "odstranit indexy nebo rozsahy")
    command("encrypt", "Zašifrovat", "použít uložené heslo")
    command("init", "První nastavení", "vytvořit nové heslo a data")
    command("password", "Změnit heslo", "bezpečně přešifrovat data")
    command("commit [text]", "Publikovat", "commit a push na GitHub")
    command("clear", "Vyčistit", "vyčistit obrazovku konzole")
    command("quit", "Ukončit", "bezpečně zavřít konzoli")
    print(f"\n  {muted('Příklady:')} delete 2 4 · delete 1,3 · delete 2-5\n")


def dispatch(cmd, args=()):
    cmd = cmd.lower()
    if cmd == "encrypt":
        encrypt_private()
    elif cmd == "init":
        initialize_private()
    elif cmd in {"password", "passwd", "change-password"}:
        change_password()
    elif cmd in {"a", "add"}:
        add()
    elif cmd in {"l", "list", "ls"}:
        listing()
    elif cmd in {"d", "delete", "del"}:
        delete(args)
    elif cmd in {"c", "commit", "push"}:
        commit(" ".join(args) or None)
    elif cmd in {"h", "help", "?"}:
        help_text()
    elif cmd in {"clear", "cls"}:
        os.system("cls" if os.name == "nt" else "clear")
        logo()
    else:
        raise DataError(f"Neznámý příkaz „{cmd}“. Napiš help pro přehled.")


def setup_completion():
    try:
        import readline
    except ImportError:
        return

    def complete(text, state):
        line = readline.get_line_buffer()
        options = (
            [name for name in COMMANDS if name.startswith(text)]
            if len(line.split()) <= 1
            else []
        )
        return (
            options[state] + (" " if state < len(options) else "")
            if state < len(options)
            else None
        )

    readline.set_completer(complete)
    readline.parse_and_bind("tab: complete")
    try:
        readline.set_auto_history(True)
    except AttributeError:
        pass


def interactive_menu():
    setup_completion()
    logo()
    try:
        auto_purge()
    except DataError as exc:
        cli_error(exc)
    while True:
        try:
            raw = input(f"  {accent('inzenyri')} {muted('›')} ").strip()
            if not raw:
                continue
            parts = shlex.split(raw)
            cmd, *args = parts
            if cmd.lower() in {"q", "quit", "exit", "konec"}:
                print(f"\n  {muted('Měj se!')}\n")
                return
            try:
                dispatch(cmd, args)
            except DataError as exc:
                cli_error(exc)
            except subprocess.CalledProcessError as exc:
                cli_error(f"Git skončil chybou ({exc.returncode}).")
        except ValueError as exc:
            cli_error(f"Neplatný příkaz: {exc}")
        except KeyboardInterrupt:
            print(f"\n  {muted('Příkaz zrušen. Pro konec napiš quit.')}\n")
        except EOFError:
            print()
            return


def main():
    if len(sys.argv) == 1:
        return interactive_menu()
    dispatch(sys.argv[1], sys.argv[2:])


if __name__ == "__main__":
    try:
        main()
    except DataError as exc:
        print(f"\n  {style('✕','31')} {style('Chyba','1;31')}: {exc}", file=sys.stderr)
        raise SystemExit(1)
    except subprocess.CalledProcessError as exc:
        print(
            f"\n  {style('✕','31')} Git skončil chybou ({exc.returncode}).",
            file=sys.stderr,
        )
        raise SystemExit(exc.returncode)
    except (KeyboardInterrupt, EOFError):
        print(f"\n  {muted('Zrušeno.')}\n", file=sys.stderr)
        raise SystemExit(130)
