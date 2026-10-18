#!/usr/bin/env python3
from __future__ import annotations
import copy
import base64, getpass, hashlib, json, os, shlex, subprocess, sys, tempfile, uuid
import re
from datetime import date, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "web"
PRIVATE_DIR = ROOT / "private"
PRIVATE_DIR.mkdir(mode=0o700, exist_ok=True)
ENC = ROOT / "data.enc.json"
PRIVATE = PRIVATE_DIR / "data.private.json"
PASSWORD_FILE = PRIVATE_DIR / ".inzenyri-password"
# Keep existing local checkouts usable after moving the private files.
for legacy_name, destination in (("data.private.json", PRIVATE), (".inzenyri-password", PASSWORD_FILE)):
    legacy = ROOT / legacy_name
    if legacy.exists() and not destination.exists():
        legacy.rename(destination)
        destination.chmod(0o600)
APP = WEB / "js" / "app.js"
ANDROID_GRADLE = ROOT / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"
GH_CONFIG_DIR = Path.home() / ".config" / "gh-inzenyri"
GITHUB_REPOSITORY = "salamek67/Inzenyri"
FORMAT = "inzenyri-encrypted-data"
VERSION = 1
ITERATIONS = 600_000
AAD = b"inzenyri-data:v1"
TYPES = {"task": "Úkol", "test": "Test", "event": "Akce"}
COMMANDS = (
    "add",
    "edit",
    "info",
    "list",
    "delete",
    "encrypt",
    "init",
    "password",
    "github-login",
    "github-status",
    "commit",
    "help",
    "clear",
    "quit",
)
SESSION_PASSWORD = None


def github_cli_environment():
    environment = os.environ.copy()
    environment["GH_CONFIG_DIR"] = str(GH_CONFIG_DIR)
    environment["GH_REPO"] = GITHUB_REPOSITORY
    environment.pop("GH_TOKEN", None)
    environment.pop("GITHUB_TOKEN", None)
    return environment


def github_cli(arguments, **kwargs):
    return subprocess.run(
        ["gh", *arguments],
        cwd=ROOT,
        env=github_cli_environment(),
        **kwargs,
    )


def github_login():
    """Sign in to the repository-specific GitHub CLI profile."""
    try:
        github_cli(
            [
                "auth", "login", "--hostname", "github.com", "--web",
                "--git-protocol", "ssh", "--skip-ssh-key",
            ],
            check=True,
        )
    except FileNotFoundError as exc:
        raise DataError("GitHub CLI (gh) není nainstalované.") from exc


def github_status():
    """Show authentication state for this repository's isolated profile."""
    try:
        result = github_cli(["auth", "status", "--hostname", "github.com"])
    except FileNotFoundError as exc:
        raise DataError("GitHub CLI (gh) není nainstalované.") from exc
    if result.returncode:
        detail = (result.stderr or result.stdout).strip()
        raise DataError(
            "Tento repozitář zatím nemá přihlášený GitHub účet. Spusť příkaz github-login."
            + (f"\n{detail}" if detail else "")
        )


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
        f"  {accent('│')}  {bold('INŽENÝŘI')}  {muted('· bezpečná správa přehledu')}        {accent('│')}"
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
            "Chybí cryptography. Spusť: python3 -m pip install -r scripts/requirements.txt"
        ) from exc


def parse_date(value):
    for fmt in ("%d.%m.%Y", "%d.%m.%y", "%Y-%m-%d"):
        try:
            return datetime.strptime(str(value).strip(), fmt).date()
        except ValueError:
            pass
    return None


def valid_fill_question(question):
    answers = question.get("answers")
    template = question.get("template")
    return (
        isinstance(template, str)
        and isinstance(answers, list)
        and 1 <= len(answers) <= 20
        and all(isinstance(answer, str) and answer.strip() for answer in answers)
        and re.findall(r"\{\{([1-9][0-9]*)\}\}", template) == [str(i) for i in range(1, len(answers) + 1)]
    )


def normalize(value):
    if (
        not isinstance(value, dict)
        or not isinstance(value.get("tasks"), list)
        or not all(isinstance(x, dict) for x in value["tasks"])
    ):
        raise DataError("Data musí být JSON objekt s polem objektů 'tasks'.")
    seen_ids = set()
    for item in value["tasks"]:
        item_id = item.get("id")
        if not isinstance(item_id, str) or not item_id.strip() or item_id.strip() in seen_ids:
            item_id = str(uuid.uuid4())
            while item_id in seen_ids:
                item_id = str(uuid.uuid4())
        else:
            item_id = item_id.strip()
        item["id"] = item_id
        seen_ids.add(item_id)
        extra_quizzes = item.get("quizzes", [])
        if not isinstance(extra_quizzes, list) or not all(isinstance(q, dict) for q in extra_quizzes):
            raise DataError("Pole quizzes musí být seznam kvízů.")
        quizzes = ([item["quiz"]] if isinstance(item.get("quiz"), dict) else []) + extra_quizzes
        for quiz in quizzes:
            questions = quiz.get("questions", [])
            if not isinstance(questions, list):
                raise DataError("Pole quiz.questions musí být seznam.")
            for question in questions:
                if isinstance(question, dict) and question.get("type") == "binary":
                    question["type"] = "choice"
                if isinstance(question, dict) and question.get("type") == "fill" and not valid_fill_question(question):
                    raise DataError("Neplatná doplňovací otázka: použij {{1}}, {{2}}, … a odpovědi ve stejném pořadí.")
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


def normalize_db_version(value):
    match = re.fullmatch(r"(\d{2})\.(\d{1,2})\.(\d{1,2})-(\d+)", str(value))
    if not match:
        return str(value)
    year, month, day, number = map(int, match.groups())
    try:
        datetime(2000 + year, month, day)
    except ValueError:
        return str(value)
    return f"{year:02d}.{month}.{day}-{number}"


def next_db_version():
    now = datetime.now()
    prefix = f"{now:%y}.{now.month}.{now.day}"
    number = 0
    if ENC.exists():
        try:
            value = read(ENC)
            previous = (
                normalize_db_version(value.get("dbVersion", ""))
                if isinstance(value, dict)
                else ""
            )
            match = re.fullmatch(r"(\d{2}\.\d{1,2}\.\d{1,2})-(\d+)", previous)
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
                return normalize_db_version(value.get("dbVersion") or "neuvedena")
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
        "dbVersion": normalize_db_version(db_version or current_db_version()),
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


def atomic_write_text(path, value):
    tmp = None
    try:
        mode = path.stat().st_mode
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=path.parent,
            prefix=f".{path.name}.",
            suffix=".tmp",
            delete=False,
        ) as f:
            tmp = f.name
            os.chmod(tmp, mode)
            f.write(value)
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
            dir=PRIVATE_DIR,
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
    data = normalize(read(PRIVATE))
    atomic_write(PRIVATE, data)
    atomic_write(ENC, envelope(data, SESSION_PASSWORD))
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
    atomic_write(PRIVATE, data)
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


def read_private():
    try:
        return normalize(read(PRIVATE))
    except DataError as exc:
        raise DataError(
            "Privátní zdroj dat nelze načíst. Oprav data.private.json nebo jej obnov."
        ) from exc


def open_data():
    global SESSION_PASSWORD
    encrypted = read(ENC)
    if SESSION_PASSWORD is None:
        SESSION_PASSWORD = load_password()
    if SESSION_PASSWORD is not None:
        try:
            decrypt(encrypted, SESSION_PASSWORD)
        except DataError:
            SESSION_PASSWORD = None
            if PASSWORD_FILE.exists():
                PASSWORD_FILE.unlink()
        else:
            return read_private(), SESSION_PASSWORD
    password = secret("Heslo k datům")
    decrypt(encrypted, password)
    data = read_private()
    SESSION_PASSWORD = password
    save_password(password)
    success("Data odemčena a heslo lokálně uloženo.")
    return data, password


def save(data, password, db_version=None):
    data = normalize(data)
    data["tasks"].sort(key=lambda x: parse_date(x.get("date", "")) or date.max)
    encrypted = envelope(data, password, db_version)
    atomic_write(PRIVATE, data)
    atomic_write(ENC, encrypted)


def ask(prompt):
    return input(f"  {accent('›')} {prompt}: ").strip()


def ask_multiline(prompt):
    return ask(f"{prompt} (\\n = nový řádek)").replace("\\n", "\n")


def parse_type(value):
    return {
        "u": "task",
        "ú": "task",
        "ukol": "task",
        "úkol": "task",
        "t": "test",
        "test": "test",
        "a": "event",
        "akce": "event",
    }.get(str(value).lower())


def choose_type(value=None):
    return parse_type(value or ask("Typ [u]kol / [t]est / [a]kce"))


def ask_number(prompt, minimum=1, maximum=None, default=None):
    try:
        entered = ask(prompt).strip()
        value = default if not entered and default is not None else int(entered)
    except ValueError as exc:
        raise DataError("Zadej celé číslo.") from exc
    if value < minimum or maximum is not None and value > maximum:
        raise DataError("Číslo je mimo povolený rozsah.")
    return value


def build_practice_question(number, count):
    heading(
        f"Otázka {number} z {count}",
        "Výběr, textová odpověď, doplňování nebo čárky ve větě",
    )
    kind = {
        "v": "choice",
        "vyber": "choice",
        "výběr": "choice",
        "choice": "choice",
        "t": "text",
        "text": "text",
        "d": "fill",
        "doplnovani": "fill",
        "doplňování": "fill",
        "fill": "fill",
        "c": "punctuation",
        "č": "punctuation",
        "carky": "punctuation",
        "čárky": "punctuation",
        "interpunkce": "punctuation",
        "punctuation": "punctuation",
    }.get(ask("Typ [v]ýběr / [t]ext / [d]oplňování / [č]árky").lower())
    if not kind:
        raise DataError("Neplatný typ otázky.")
    if kind == "choice":
        prompt = ask_multiline("Znění otázky")
        if not prompt:
            raise DataError("Znění otázky nesmí být prázdné.")
        option_count = ask_number("Počet možností", 2, 4)
        options = [ask(f"Možnost {index}") for index in range(1, option_count + 1)]
        if any(not option for option in options):
            raise DataError("Všechny možnosti musí být vyplněné.")
        answer = ask_number(f"Správná možnost [1-{option_count}]", 1, option_count) - 1
        question = {
            "type": "choice",
            "prompt": prompt,
            "options": options,
            "answer": answer,
        }
    elif kind == "fill":
        prompt = ask_multiline("Zadání otázky")
        template = ask_multiline("Text s políčky {{1}}, {{2}}, … (např. {{1}} H₂ + {{2}} O₂ → {{3}} H₂O)")
        answers = [value.strip() for value in ask("Správné hodnoty políček v pořadí (odděl |)").split("|")]
        question = {
            "type": "fill", "prompt": prompt, "template": template, "answers": answers,
            "numeric": ask("Vyplňovat jen celá nezáporná čísla? [a/n]").lower() in {"a", "ano"},
        }
        if not prompt or not valid_fill_question(question):
            raise DataError("Vyplň zadání, očíslovaná políčka {{1}}, {{2}}, … a jednu odpověď pro každé políčko.")
        if question["numeric"] and any(not re.fullmatch(r"[0-9]+", answer) for answer in answers):
            raise DataError("Číselná políčka musí mít odpovědi zapsané číslicemi.")
        if not question["numeric"]:
            question["ignoreCase"] = ask("Ignorovat velikost písmen? [a/n]").lower() in {"a", "ano"}
    elif kind == "text":
        prompt = ask_multiline("Znění otázky")
        answers = [
            value.strip()
            for value in ask("Správné odpovědi (odděl |)").split("|")
            if value.strip()
        ]
        if not prompt:
            raise DataError("Znění otázky nesmí být prázdné.")
        if not answers:
            raise DataError("Je potřeba alespoň jedna správná odpověď.")
        question = {
            "type": "text",
            "prompt": prompt,
            "answers": answers,
            "ignoreCase": ask("Ignorovat velikost písmen? [a/n]").lower()
            in {"a", "ano"},
        }
    else:
        sentence = ask_multiline("Věta bez čárek")
        if not sentence or "," in sentence:
            raise DataError("Výchozí věta musí být vyplněná a bez čárek.")
        answers = [
            value.strip()
            for value in ask_multiline(
                "Správné věty s čárkami (varianty odděl |)"
            ).split("|")
            if value.strip()
        ]
        if not answers or any("," not in answer for answer in answers):
            raise DataError("Každá správná varianta musí obsahovat čárku.")
        without_commas = lambda value: " ".join(
            value.replace(",", " ").casefold().split()
        )
        if any(
            without_commas(answer) != without_commas(sentence) for answer in answers
        ):
            raise DataError(
                "Správná varianta se smí od výchozí věty lišit jen čárkami a mezerami."
            )
        question = {
            "type": "punctuation",
            "prompt": "Doplň čárky do věty.",
            "sentence": sentence,
            "answers": answers,
            "ignoreCase": ask("Ignorovat velikost písmen? [a/n]").lower()
            in {"a", "ano"},
        }
    explanation = ask_multiline("Vysvětlení při chybě (volitelné)")
    if explanation:
        question["explanation"] = explanation
    return question


def build_practice():
    heading(
        "Editor procvičování",
        "Výběr možností, textová odpověď nebo doplňování čárek.",
    )
    while True:
        try:
            count = ask_number("Počet otázek (Enter nebo 0 = přeskočit)", 0, 100, default=0)
            break
        except DataError as exc:
            cli_error(exc)
    if count == 0:
        return None
    shuffle = ask("Náhodně promíchat pořadí otázek? [a/n]").lower() in {"a", "ano"}

    questions = []
    for number in range(1, count + 1):
        while True:
            try:
                questions.append(build_practice_question(number, count))
                break
            except DataError as exc:
                cli_error(exc)
                warning("Otázka nebyla uložena. Zadej ji znovu.")
    return {"shuffle": shuffle, "questions": questions}


def build_practice_sets():
    quizzes = []
    while True:
        title = ask("Název kvízu (např. Sčítání; prázdné = Základní procvičování)").strip()
        quiz = build_practice()
        if not quiz:
            break
        quiz["id"] = str(uuid.uuid4())
        quiz["title"] = title or ("Základní procvičování" if not quizzes else f"Kvíz {len(quizzes) + 1}")
        quizzes.append(quiz)
        if ask("Přidat k tomuto testu další kvíz? [a/n]").lower() not in {"a", "ano"}:
            break
    return quizzes


def set_practice_sets(item, quizzes):
    item.pop("quiz", None)
    item.pop("quizzes", None)
    if quizzes:
        item["quiz"] = quizzes[0]
        if len(quizzes) > 1:
            item["quizzes"] = quizzes[1:]


AI_REVIEW_NAMES = {"chatgpt": "ChatGPT", "claude": "Claude", "gemini": "Gemini", "deepseek": "DeepSeek"}


def choose_ai_reviews(current=None):
    current = current if isinstance(current, list) else []
    selected = [key for key in AI_REVIEW_NAMES if key in current]
    print("  AI kontrola: " + (", ".join(AI_REVIEW_NAMES[key] for key in selected) or "žádná"))
    while True:
        value = ask("AI kontrola [1] ChatGPT / [2] Claude / [3] Gemini / [4] DeepSeek (čísla čárkou, Enter ponechat, - odebrat)").strip()
        if not value:
            return selected
        if value == "-":
            return []
        parts = value.replace(" ", ",").split(",")
        choices = {part for part in parts if part}
        if choices and choices <= {"1", "2", "3", "4"}:
            return [key for i, key in enumerate(AI_REVIEW_NAMES, 1) if str(i) in choices]
        cli_error("Vyber čísla 1–4, Enter nebo -.")


def add(type_argument=None):
    heading("Nová položka", "Úkol, test nebo školní akce")
    data, password = open_data()
    kind = choose_type(type_argument)
    if not kind:
        raise DataError("Neplatný typ.")
    name = ask("Název")
    when = ask("Datum (dd.mm.yyyy)")
    text = ask_multiline("Popis")
    if not parse_date(when):
        raise DataError("Neplatné datum.")
    if kind == "task":
        solution = ask_multiline("Řešení v Markdownu (volitelné)")
    else:
        solution = ""
    item = {
        "id": str(uuid.uuid4()),
        "type": kind,
        "name": name,
        "date": when,
        "task": text,
        "solution": solution,
    }
    if kind == "task":
        item["aiReviews"] = choose_ai_reviews()
    if kind == "test":
        set_practice_sets(item, build_practice_sets())
    data["tasks"].append(item)
    save(data, password)
    success("Položka byla uložena do privátních dat a zašifrována.")


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


def parse_item_index(value, item_count, prompt):
    raw = str(value).strip() if value is not None else ask(prompt)
    if not raw.isdigit():
        raise DataError("Index musí být nezáporné celé číslo.")
    index = int(raw)
    if index >= item_count:
        raise DataError(f"Index {index} neexistuje.")
    return index


def ask_editable(prompt, current):
    editable = str(current).replace("\n", "\\n")
    try:
        import readline
    except ImportError:
        print(f"  {muted('Současná hodnota:')} {editable}")
        return ask(prompt)

    previous_completer = readline.get_completer()

    def prefill():
        readline.insert_text(editable)
        readline.redisplay()

    readline.set_completer(None)
    readline.set_startup_hook(prefill)
    try:
        return input(f"  {accent('›')} {prompt}: ").strip()
    finally:
        readline.set_startup_hook()
        readline.set_completer(previous_completer)


def edit_value(label, current, *, multiline=False, clearable=False):
    hint = "uprav šipkami, Enter = potvrdit"
    if clearable:
        hint += ", - = smazat"
    value = ask_editable(f"{label} ({hint})", current)
    if not value:
        return current
    if clearable and value == "-":
        return ""
    return value.replace("\\n", "\n") if multiline else value


def info(index=None):
    data, _ = open_data()
    items = current(data)
    display(items)
    if not items:
        return

    item = items[parse_item_index(index, len(items), "Index k zobrazení")]
    heading(item.get("name") or "Bez názvu", TYPES.get(item.get("type"), "Položka"))
    print(f"  {muted('Datum:')} {item.get('date') or 'neuvedeno'}")
    print(f"  {muted('Popis:')} {item.get('task') or '—'}")
    if item.get("type") == "task" and isinstance(item.get("aiReviews"), list):
        names = [name for key, name in AI_REVIEW_NAMES.items() if key in item["aiReviews"]]
        if names:
            print(f"  {muted('AI kontrola:')} {', '.join(names)}")
    if item.get("solution"):
        print(f"  {muted('Řešení:')} {item['solution']}")

    quiz = item.get("quiz")
    for index, extra in enumerate(item.get("quizzes", []), 2):
        print(f"  {muted('Další kvíz:')} {extra.get('title') or f'Kvíz {index}'} · {len(extra.get('questions', []))} otázek")
    questions = quiz.get("questions", []) if isinstance(quiz, dict) else []
    if not questions:
        print(f"  {muted('Procvičování:')} —")
        return
    shuffle = "ano" if quiz.get("shuffle") else "ne"
    print(f"  {muted('Procvičování:')} {len(questions)} otázek · promíchání: {shuffle}")
    for number, question in enumerate(questions, 1):
        if not isinstance(question, dict):
            print(f"\n  {accent(str(number) + '.')} {style('Neplatná otázka', '31')}")
            continue
        kind = question.get("type")
        prompt = question.get("prompt") or question.get("sentence") or "Bez zadání"
        print(f"\n  {accent(str(number) + '.')} {bold(prompt)}")
        if kind == "choice":
            options = question.get("options", [])
            answer = question.get("answer")
            for option_index, option in enumerate(options):
                marker = "✓" if option_index == answer else "·"
                print(f"      {marker} {option}")
        else:
            answers = question.get("answers", [])
            if kind == "punctuation":
                print(f"      {muted('Věta:')} {question.get('sentence', '')}")
            if kind == "fill":
                print(f"      {muted('Doplňování:')} {question.get('template', '')}")
            print(f"      {muted('Odpověď:')} {' | '.join(map(str, answers)) or '—'}")
            ignore_case = "ano" if question.get("ignoreCase") else "ne"
            print(f"      {muted('Ignorovat velikost:')} {ignore_case}")
        if question.get("explanation"):
            print(f"      {muted('Vysvětlení:')} {question['explanation']}")


def edit(index=None):
    data, password = open_data()
    items = current(data)
    display(items)
    if not items:
        return

    target = items[parse_item_index(index, len(items), "Index k úpravě")]
    updated = copy.deepcopy(target)
    heading(
        f"Upravit · {target.get('name', 'Bez názvu')}",
        "Text je předvyplněný; šipkami oprav jen potřebnou část.",
    )
    updated["name"] = edit_value("Název", str(updated.get("name", "")))
    new_date = edit_value("Datum (dd.mm.yyyy)", str(updated.get("date", "")))
    if not parse_date(new_date):
        raise DataError("Neplatné datum.")
    updated["date"] = new_date
    updated["task"] = edit_value(
        "Popis", str(updated.get("task", "")), multiline=True, clearable=True
    )
    if updated.get("type") == "task":
        updated["solution"] = edit_value(
            "Řešení v Markdownu",
            str(updated.get("solution", "")),
            multiline=True,
            clearable=True,
        )

    if updated.get("type") == "task":
        updated["aiReviews"] = choose_ai_reviews(updated.get("aiReviews"))

    if updated.get("type") == "test" or "quiz" in updated or "quizzes" in updated:
        while True:
            action = (
                ask("Kvízy [p]onechat / [a] přidat / [u] nahradit všechny / [s]mazat všechny").lower() or "p"
            )
            if action in {"p", "ponechat"}:
                break
            if action in {"u", "upravit"}:
                set_practice_sets(updated, build_practice_sets())
                break
            if action in {"a", "pridat", "přidat"}:
                existing = ([updated["quiz"]] if isinstance(updated.get("quiz"), dict) else []) + updated.get("quizzes", [])
                set_practice_sets(updated, existing + build_practice_sets())
                break
            if action in {"s", "smazat"}:
                set_practice_sets(updated, [])
                break
            cli_error("Vyber p, a, u nebo s.")

    if updated == target:
        warning("Nebyla provedena žádná změna.")
        return
    heading("Náhled upravené položky")
    display([updated])
    if ask("Uložit změny? [a/n]").lower() not in {"a", "ano"}:
        print("Zrušeno.")
        return
    target.clear()
    target.update(updated)
    save(data, password)
    success("Položka byla upravena v privátních datech a znovu zašifrována.")


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
    success(f"Z privátních i šifrovaných dat smazáno položek: {len(targets)}.")


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


def web_version():
    try:
        source = APP.read_text(encoding="utf-8")
    except OSError as exc:
        raise DataError("Soubor app.js nelze načíst.") from exc
    match = re.search(r'(?m)^const PAGE_VERSION = "(\d+)\.(\d+)\.(\d+)";$', source)
    if not match:
        raise DataError("V app.js chybí platná konstanta PAGE_VERSION.")
    return source, match, tuple(map(int, match.groups()))


def offer_web_version_bump():
    source, match, version = web_version()
    current = ".".join(map(str, version))
    if ask(f"Zvýšit verzi webu {current}? [a/n]").lower() not in {"a", "ano"}:
        return None

    level = {
        "1": 0,
        "major": 0,
        "ma": 0,
        "2": 1,
        "minor": 1,
        "mi": 1,
        "3": 2,
        "patch": 2,
        "p": 2,
    }.get(ask("Index [1] major / [2] minor / [3] patch").lower())
    if level is None:
        raise DataError("Neplatný index verze. Použij 1, 2 nebo 3.")

    bumped = list(version)
    bumped[level] += 1
    for index in range(level + 1, 3):
        bumped[index] = 0
    new_version = ".".join(map(str, bumped))
    updated = (
        source[: match.start()]
        + f'const PAGE_VERSION = "{new_version}";'
        + source[match.end() :]
    )
    atomic_write_text(APP, updated)
    success(f"Verze webu zvýšena: {current} → {new_version}.")
    return new_version


def android_version():
    try:
        source = ANDROID_GRADLE.read_text(encoding="utf-8")
    except OSError as exc:
        raise DataError("Soubor app/build.gradle.kts nelze načíst.") from exc
    pattern = re.compile(
        r'(?m)^(\s*versionName = providers\.gradleProperty\("appVersionName"\)\.orElse\(")'
        r'(\d+)\.(\d+)\.(\d+)("\)\.get\(\))$'
    )
    match = pattern.search(source)
    if not match:
        raise DataError("V app/build.gradle.kts chybí výchozí verze Android aplikace.")
    return source, match, tuple(map(int, match.groups()[1:4]))


def offer_android_version_bump():
    source, match, version = android_version()
    current = ".".join(map(str, version))
    if ask(f"Zvýšit verzi Android aplikace {current}? [a/n]").lower() not in {"a", "ano"}:
        return None

    try:
        auth = github_cli(["auth", "status"], capture_output=True, text=True)
    except FileNotFoundError as exc:
        raise DataError(
            "Pro Android release nainstaluj GitHub CLI (gh) a přihlas vyhrazený účet příkazem:\n"
            f'GH_CONFIG_DIR={shlex.quote(str(GH_CONFIG_DIR))} gh auth login --hostname github.com'
        ) from exc
    if auth.returncode:
        detail = (auth.stderr or auth.stdout).strip()
        raise DataError(
            "Přihlas účet pro releasy příkazem:\n"
            f'GH_CONFIG_DIR={shlex.quote(str(GH_CONFIG_DIR))} gh auth login --hostname github.com'
            + (f"\n{detail}" if detail else "")
        )

    level = {
        "1": 0,
        "major": 0,
        "ma": 0,
        "2": 1,
        "minor": 1,
        "mi": 1,
        "3": 2,
        "patch": 2,
        "p": 2,
    }.get(ask("Index [1] major / [2] minor / [3] patch").lower())
    if level is None:
        raise DataError("Neplatný index verze. Použij 1, 2 nebo 3.")

    bumped = list(version)
    bumped[level] += 1
    for index in range(level + 1, 3):
        bumped[index] = 0
    new_version = ".".join(map(str, bumped))
    release_tag = new_version
    if github_release_exists(release_tag):
        raise DataError(f"GitHub Release {release_tag} už existuje.")
    updated = source[: match.start(2)] + new_version + source[match.end(4) :]
    atomic_write_text(ANDROID_GRADLE, updated)
    success(f"Verze Android aplikace zvýšena: {current} → {new_version}.")
    return current, new_version


def consume_changelog_category(section):
    try:
        changelog = CHANGELOG.read_text(encoding="utf-8")
    except OSError as exc:
        raise DataError("Soubor CHANGELOG.md nelze načíst.") from exc
    section_pattern = re.compile(
        rf"(?ms)^(## {re.escape(section)})\n(.*?)(?=^## |\Z)"
    )
    match = section_pattern.search(changelog)
    if not match:
        raise DataError(f"V CHANGELOG.md chybí část {section}.")
    body = match.group(2)
    entries = body.strip()
    updated = changelog[: match.start(2)] + "\n" + changelog[match.end(2) :]
    atomic_write_text(CHANGELOG, updated.rstrip() + "\n")
    return entries


def github_release_exists(tag):
    result = github_cli(["release", "view", tag], capture_output=True, text=True)
    return result.returncode == 0


def commit_build_targets(message):
    prefix = message.lstrip().lower()
    if prefix.startswith("[ci skip]"):
        return False, False
    if prefix.startswith("[app skip]"):
        return True, False
    if prefix.startswith("[page skip]"):
        return False, True
    return True, True


def infer_commit_prefix(changed_paths):
    web_files = {
        "index.html",
        "app.js",
        "web-settings.js",
        "web-qr.js",
        "web-formatting.js",
        "practice-progress.js",
        "web-offline.js",
        "sw.js",
        "favicon.ico",
        "favicon.png",
        "apple-touch-icon.png",
        "data.enc.json",
        ".github/workflows/static.yml",
    }
    android_files = {
        ".github/workflows/android.yml",
    }
    builds_web = any(path in web_files or path == "web" or path.startswith("web/") or path == "vendor" or path.startswith("vendor/") for path in changed_paths)
    builds_android = any(
        path in android_files or path == "app" or path.startswith("app/")
        for path in changed_paths
    )
    if builds_web and builds_android:
        return ""
    if builds_android:
        return "[page skip] "
    if builds_web:
        return "[app skip] "
    return "[ci skip] "


def ensure_repository_up_to_date():
    """Check the actual push target before changing versions or encrypted data."""
    try:
        branch = subprocess.run(
            ["git", "branch", "--show-current"],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.strip()
        if branch != "main":
            raise DataError(
                "Commit přes skript vyžaduje větev main. "
                "Nejdřív na ni přejdi a zachovej své rozpracované změny."
            )
        print(f"  {accent('›')} Kontroluji aktuálnost vůči origin/main…")
        subprocess.run(
            ["git", "fetch", "--no-tags", "origin",
             "+refs/heads/main:refs/remotes/origin/main"],
            cwd=ROOT, check=True, capture_output=True, text=True,
        )
        counts = subprocess.run(
            ["git", "rev-list", "--left-right", "--count",
             "HEAD...refs/remotes/origin/main"],
            cwd=ROOT, check=True, capture_output=True, text=True,
        ).stdout.split()
        ahead, behind = map(int, counts)
    except (subprocess.CalledProcessError, OSError, ValueError) as exc:
        raise DataError(
            "Nelze ověřit aktuálnost repozitáře. Zkontroluj připojení, "
            "přístup k origin a dostupnost větve main; commit byl přerušen."
        ) from exc
    if behind:
        if ahead:
            raise DataError(
                f"Historie main a origin/main se rozchází: {ahead} lokálních "
                f"a {behind} vzdálených commitů. Nejdřív sjednoť historii; "
                "příprava commitu byla zastavena."
            )
        raise DataError(
            f"Větev main zaostává za origin/main o {behind} commitů. "
            "Nejdřív načti vzdálené změny (git pull --ff-only), "
            "přitom zachovej rozpracovaná data. Příprava commitu byla zastavena."
        )
    if ahead:
        success(f"Repozitář obsahuje změny z origin/main; {ahead} lokálních commitů čeká na push.")
    else:
        success("Repozitář je aktuální vůči origin/main.")


def commit(message=None):
    if not ENC.exists():
        raise DataError("Nejdřív vytvoř data.enc.json příkazem encrypt.")
    ensure_repository_up_to_date()
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
        "web-settings.js",
        "web-qr.js",
        "web-formatting.js",
        "practice-progress.js",
        "web-offline.js",
        "sw.js",
        "favicon.ico",
        "favicon.png",
        "apple-touch-icon.png",
        ".github/workflows/static.yml",
    )
    changed_paths = []
    for line in changed:
        path = line[3:].split(" -> ")[-1].strip('"')
        changed_paths.append(path)
    touches_web = any(path in web_files or path == ".github/" or path == "web" or path.startswith("web/") or path == "vendor" or path.startswith("vendor/") for path in changed_paths)
    touches_android_app = any(
        path == "app" or path.startswith("app/") for path in changed_paths
    )
    touches_android = touches_android_app or ".github/workflows/android.yml" in changed_paths
    touches_data = "data.enc.json" in changed_paths
    android_bump = None
    android_previous_version = None
    if touches_web:
        offer_web_version_bump()
    if touches_android_app:
        android_bump_result = offer_android_version_bump()
        if android_bump_result:
            android_previous_version, android_bump = android_bump_result
    data, password = open_data()
    if touches_data:
        save(data, password, next_db_version())
        success("Data ověřena, zvýšena verze DB a obsah znovu zašifrován.")
    else:
        print(f"  {style('✓','32')} {muted('Šifrovaná data ověřena; DB se nemění.')}\n")
    candidates = [
        "web", "scripts", "docs", "README.md", "LICENSE", "data.enc.json", "script.py", "preview.sh",
        ".gitignore", "AGENTS.md", "CHANGELOG.md", ".github", "app",
        "index.html", "app.js", "web-settings.js", "web-qr.js", "web-formatting.js",
        "practice-progress.js", "web-offline.js", "sw.js", "vendor", "favicon.ico",
        "favicon.png", "apple-touch-icon.png", "requirements.txt",
    ]
    files = [path for path in candidates if (ROOT / path).exists() or subprocess.run(
        ["git", "ls-files", "--error-unmatch", "--", path], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0]
    message = (
        message
        or ask("Zpráva commitu")
        or "Aktualizace přehledu"
    ).strip()
    if not message.lower().startswith(("[app skip]", "[page skip]", "[ci skip]")):
        message = infer_commit_prefix(changed_paths) + message
    build_web, build_android = commit_build_targets(message)
    skip_prefix = re.match(r"(?i)^\[(?:app|page|ci) skip\]\s*", message)
    prefix_text = skip_prefix.group(0) if skip_prefix else ""
    summary = message[len(prefix_text) :].strip()
    android_release_notes = None
    if android_bump:
        if not build_android:
            raise DataError("Bump Android verze vyžaduje sestavení aplikace. Odstraň prefix [app skip] nebo [ci skip].")
        app_entries = consume_changelog_category("Android")
        if not app_entries:
            app_entries = f"- {summary}"
        compare_url = (
            "https://github.com/salamek67/Inzenyri/compare/"
            f"{android_previous_version}...{android_bump}"
        )
        android_release_notes = (
            f"{app_entries}\n\n"
            f"**Full Changelog**: {compare_url}"
        )
    if not build_web and not build_android:
        print(f"  {muted('CI přeskočeno: web ani Android se nebuildí.')}")
    elif not build_android:
        print(f"  {muted('CI přeskočí Android; web se nasadí.')}")
    elif not build_web:
        print(f"  {muted('CI přeskočí web; Android se sestaví.')}")
    else:
        print(f"  {muted('CI sestaví web i Android.')}")
    subprocess.run(["git", "add", *files], cwd=ROOT, check=True)
    subprocess.run(["git", "commit", "-m", message], cwd=ROOT, check=True)
    subprocess.run(["git", "push", "-u", "origin", "main"], cwd=ROOT, check=True)
    if android_bump:
        github_cli(
            [
                "release", "create", android_bump,
                "--title", android_bump,
                "--notes", android_release_notes,
                "--target", "main",
            ],
            check=True,
        )
    if android_bump:
        success("Poznámky releasu byly použity a changelog vyčištěn ve stejném commitu.")


def help_text():
    heading("Příkazy", "Příkaz můžeš napsat celý nebo použít krátkou zkratku.")
    command("add [u|t|a]", "Přidat", "nový úkol, test nebo akci")
    command("edit [id]", "Upravit", "změnit existující položku")
    command("info [id]", "Detail", "zobrazit položku a procvičování")
    command("list", "Přehled", "vypsat aktuální položky")
    command("delete [id…]", "Smazat", "odstranit indexy nebo rozsahy")
    command("encrypt", "Zašifrovat", "použít uložené heslo")
    command("init", "První nastavení", "vytvořit nové heslo a data")
    command("password", "Změnit heslo", "bezpečně přešifrovat data")
    command("github-login", "Přihlásit GitHub", "uložit přihlášení pro tento repozitář")
    command("github-status", "GitHub účet", "ověřit přihlášení používané pro releasy")
    command("commit [text]", "Publikovat", "commit a push na GitHub")
    command("clear", "Vyčistit", "vyčistit obrazovku konzole")
    command("quit", "Ukončit", "bezpečně zavřít konzoli")
    print(f"\n  {muted('Příklady:')} add u · info 2 · edit 2 · delete 2-5\n")
    print(
        f"  {muted('Matematika:')} $a /cdot sqrt(x)^2 /aproxeq y$ · obyčejný dolar: \\$\n"
    )
    print(f"  {muted('Kombinace:')} **$x^2$** · $výsledek = **42**$\n")


def dispatch(cmd, args=()):
    cmd = cmd.lower()
    if cmd == "encrypt":
        encrypt_private()
    elif cmd == "init":
        initialize_private()
    elif cmd in {"password", "passwd", "change-password"}:
        change_password()
    elif cmd in {"github-login", "gh-login"}:
        github_login()
    elif cmd in {"github-status", "gh-status"}:
        github_status()
    elif cmd in {"a", "add"}:
        if len(args) > 1:
            raise DataError("Příkaz add přijímá nejvýše jednu zkratku typu.")
        add(args[0] if args else None)
    elif cmd in {"l", "list", "ls"}:
        listing()
    elif cmd in {"i", "info", "detail"}:
        if len(args) > 1:
            raise DataError("Příkaz info přijímá nejvýše jeden index.")
        info(args[0] if args else None)
    elif cmd in {"e", "edit", "upravit"}:
        if len(args) > 1:
            raise DataError("Příkaz edit přijímá nejvýše jeden index.")
        edit(args[0] if args else None)
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
        parts = line.lstrip().split()
        if not parts or (len(parts) == 1 and not line.endswith(" ")):
            options = [name for name in COMMANDS if name.startswith(text)]
        elif parts[0].lower() in {"a", "add"} and len(parts) <= 2:
            options = [kind for kind in ("u", "t", "a") if kind.startswith(text)]
        else:
            options = []
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
                cli_error(f"Příkaz skončil chybou ({exc.returncode}).")
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
            f"\n  {style('✕','31')} Příkaz skončil chybou ({exc.returncode}).",
            file=sys.stderr,
        )
        raise SystemExit(exc.returncode)
    except (KeyboardInterrupt, EOFError):
        print(f"\n  {muted('Zrušeno.')}\n", file=sys.stderr)
        raise SystemExit(130)
