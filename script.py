#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

FILE = Path(__file__).with_name("data.js")
PREFIX = "var data = "
DEFAULT_DATA = {"tasks": [], "schedule": []}
TYPE_NAMES = {
    "holiday": "Prázdniny",
    "substitution": "Suplování",
    "excursion": "Exkurze/Výlet",
    "free": "Volno",
}
DAY_NAMES = {
    1: "Pondělí",
    2: "Úterý",
    3: "Středa",
    4: "Čtvrtek",
    5: "Pátek",
    6: "Sobota",
    7: "Neděle",
}
WEEK_NAMES = {
    -1: "minulý",
    0: "tento",
    1: "příští",
}
WEEK_TYPE_NAMES = {
    "both": "obě",
    "odd": "lichý",
    "even": "sudý",
}


class Colors:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    RED = "\033[31m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    BLUE = "\033[34m"
    MAGENTA = "\033[35m"
    CYAN = "\033[36m"

    @classmethod
    def disable(cls):
        for attr in ("RESET", "BOLD", "DIM", "RED", "GREEN", "YELLOW", "BLUE", "MAGENTA", "CYAN"):
            setattr(cls, attr, "")


if not sys.stdout.isatty():
    Colors.disable()


def parse_date(value: str) -> date | None:
    raw = str(value).strip()
    if not raw:
        return None
    for fmt in ("%d.%m.%Y", "%d.%m.%y", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, fmt).date()
        except ValueError:
            pass
    parts = raw.split(".")
    if len(parts) == 3:
        try:
            d, m, y = int(parts[0]), int(parts[1]), int(parts[2])
            if y < 100:
                y += 2000
            return date(y, m, d)
        except ValueError:
            pass
    return None


def format_date(dt: date) -> str:
    return dt.strftime("%d.%m.%Y")


def parse_date_or_range(value: str) -> list[date] | None:
    """Převede řetězec na seznam dat (jediné datum nebo rozmezí datum-datum)."""
    raw = str(value).strip()
    if not raw:
        return None

    # Kontrola rozmezí (odděleno pomlčkou nebo spojovníkem)
    if "-" in raw and not raw.startswith("-"):
        parts = raw.split("-")
        if len(parts) == 2:
            start_dt = parse_date(parts[0])
            end_dt = parse_date(parts[1])
            if start_dt and end_dt:
                if start_dt > end_dt:
                    start_dt, end_dt = end_dt, start_dt
                dates = []
                curr = start_dt
                while curr <= end_dt:
                    dates.append(curr)
                    curr += timedelta(days=1)
                return dates

    # Samostatné datum
    dt = parse_date(raw)
    if dt is not None:
        return [dt]

    return None


def parse_day(value: str) -> int | None:
    raw = str(value).strip().lower().replace(".", "")
    if raw.isdigit():
        day = int(raw)
        if 1 <= day <= 7:
            return day

    aliases = {
        "po": 1,
        "pondeli": 1,
        "pondělí": 1,
        "ut": 2,
        "utery": 2,
        "úterý": 2,
        "st": 3,
        "streda": 3,
        "středa": 3,
        "ct": 4,
        "ctvrtek": 4,
        "čtvrtek": 4,
        "pa": 5,
        "patek": 5,
        "pátek": 5,
        "so": 6,
        "sobota": 6,
        "ne": 7,
        "nedele": 7,
        "neděle": 7,
    }
    return aliases.get(raw)


def parse_week(value: str) -> int | None:
    raw = str(value).strip().lower().replace(".", "")
    if raw in {"", "0", "tento", "ted", "teď", "ted'", "this"}:
        return 0
    if raw in {"-1", "minuly", "minulý", "minulý týden", "minuly tyden"}:
        return -1
    if raw in {"1", "pristi", "příští", "pristi tyden", "příští týden"}:
        return 1
    return None


def parse_week_type(value: str) -> str | None:
    raw = str(value).strip().lower().replace(".", "")
    aliases = {
        "": "both",
        "o": "both",
        "obě": "both",
        "obe": "both",
        "both": "both",
        "all": "both",
        "a": "both",
        "0": "both",
        "l": "odd",
        "lichý": "odd",
        "lichy": "odd",
        "odd": "odd",
        "1": "odd",
        "s": "even",
        "sudý": "even",
        "sudy": "even",
        "even": "even",
        "2": "even",
    }
    return aliases.get(raw)


def format_day(value: str | int) -> str:
    day = parse_day(str(value))
    return DAY_NAMES.get(day, str(value))


def format_week(value: str | int | None) -> str:
    week = parse_week(str(value)) if value is not None else 0
    return WEEK_NAMES.get(week, str(value))


def format_week_type(value: str | None) -> str:
    week_type = parse_week_type(str(value)) if value is not None else "both"
    return WEEK_TYPE_NAMES.get(week_type, str(value))


def format_schedule_week_type(item: dict) -> str:
    return WEEK_TYPE_NAMES.get(schedule_item_week_type(item), "obě")


def today() -> date:
    return date.today()


def iso_week_parity(offset: int = 0) -> int:
    base = today()
    dt = date.fromordinal(base.toordinal() + offset * 7)
    iso_year, iso_week, _ = dt.isocalendar()
    return iso_week % 2


def week_parity_hint(offset: int = 0) -> str:
    p = iso_week_parity(offset)
    label = "lichý" if p else "sudý"
    names = {0: "Tento", 1: "Příští", -1: "Minulý"}
    return f"{names.get(offset, '')} týden je {label} (L=lichý, S=sudý)"


def normalize_data(value: object) -> dict:
    if isinstance(value, list):
        return {"tasks": value, "schedule": []}

    if not isinstance(value, dict):
        return {"tasks": [], "schedule": []}

    tasks = value.get("tasks", [])
    schedule = value.get("schedule", [])
    return {
        "tasks": tasks if isinstance(tasks, list) else [],
        "schedule": schedule if isinstance(schedule, list) else [],
    }


def purge_old(items: list[dict]) -> list[dict]:
    current = today()
    kept = []
    for item in items:
        item_date = parse_date(str(item.get("date", "")))
        if item_date is not None and item_date >= current:
            kept.append(item)
    return kept


def sort_tasks(items: list[dict]) -> list[dict]:
    def sort_key(item: dict) -> tuple[int, str]:
        item_date = parse_date(str(item.get("date", "")))
        if item_date is None:
            return (1, str(item.get("date", "")))
        return (0, item_date.isoformat())

    return sorted(items, key=sort_key)


def purge_old_schedule(items: list[dict]) -> list[dict]:
    current = today()
    # Start of previous week (Monday)
    days_since_monday = current.weekday()
    prev_week_start = current - timedelta(days=days_since_monday + 7)
    kept = []
    for item in items:
        item_date = parse_date(str(item.get("date", "")))
        date_to = parse_date(str(item.get("dateTo", "")))
        if item_date is not None:
            if item_date >= prev_week_start:
                kept.append(item)
        elif date_to is not None:
            if date_to >= prev_week_start:
                kept.append(item)
        else:
            kept.append(item)
    return kept


def sort_schedule(items: list[dict]) -> list[dict]:
    def sort_key(item: dict) -> tuple[int, str, int, int, str]:
        week_type = schedule_item_week_type(item)
        day = parse_day(str(item.get("day", ""))) or 99
        item_type = item.get("type", "")
        type_order = {"holiday": 0, "excursion": 1, "substitution": 2, "": 3}.get(item_type, 3)
        hour_raw = str(item.get("hour", "")).strip()
        hour = int(hour_raw) if hour_raw.isdigit() else 0
        date_obj = parse_date(str(item.get("date", "")))
        date_iso = date_obj.isoformat() if date_obj else ""
        return (type_order, date_iso, day, hour, str(item.get("subject", "")))

    return sorted(items, key=sort_key)


def load_data() -> dict:
    if not FILE.exists():
        return normalize_data(DEFAULT_DATA)

    text = FILE.read_text(encoding="utf-8").strip()
    if not text:
        return normalize_data(DEFAULT_DATA)

    match = re.search(r"var\s+data\s*=\s*([\s\S]*?)\s*;?\s*$", text)
    if not match:
        raise ValueError("Soubor data.js nemá očekávaný formát.")

    data = json.loads(match.group(1))
    return normalize_data(data)


def save_data(data: dict) -> None:
    normalized = normalize_data(data)
    normalized["tasks"] = sort_tasks(purge_old(normalized["tasks"]))
    normalized["schedule"] = sort_schedule(purge_old_schedule(normalized["schedule"]))
    payload = json.dumps(normalized, ensure_ascii=False, indent=2)
    FILE.write_text(f"{PREFIX}{payload};\n", encoding="utf-8")


def ask(prompt: str) -> str:
    return input(f"{Colors.CYAN}{prompt}{Colors.RESET} ").strip()


def confirm(prompt: str) -> bool:
    answer = input(f"{Colors.YELLOW}{prompt} [a/n]: {Colors.RESET} ").strip().lower()
    return answer in ("a", "ano", "y", "yes")


def info(msg: str) -> None:
    print(f"{Colors.GREEN}{msg}{Colors.RESET}")


def warn(msg: str) -> None:
    print(f"{Colors.YELLOW}{msg}{Colors.RESET}")


def error(msg: str) -> None:
    print(f"{Colors.RED}{msg}{Colors.RESET}")


def heading(msg: str) -> None:
    print(f"\n{Colors.BOLD}{Colors.MAGENTA}── {msg} ──{Colors.RESET}")


def add_task() -> None:
    data = load_data()

    heading("Přidat úkol")
    name = ask("Název: ")
    date_value = ask("Datum (dd.mm.yyyy): ")
    task = ask("Úkol: ")
    solution = ask("Řešení: ")

    if parse_date(date_value) is None:
        error("Neplatné datum.")
        return

    data["tasks"].append(
        {
            "name": name,
            "date": date_value,
            "task": task,
            "solution": solution,
        }
    )
    save_data(data)
    info("Úkol uložen.")


def delete_task(index_value: str | None = None) -> None:
    data = load_data()
    items = sort_tasks(purge_old(data["tasks"]))

    if not items:
        warn("Žádné úkoly k odstranění.")
        return

    heading("Smazat úkol")
    for i, item in enumerate(items):
        print(f"  {Colors.CYAN}{i}{Colors.RESET}: {item.get('name', '')} [{Colors.DIM}{item.get('date', '')}{Colors.RESET}]")

    if index_value is None:
        index_value = ask("Smazat index: ")

    try:
        index = int(index_value)
    except ValueError:
        error("Index musí být číslo.")
        return

    if index < 0 or index >= len(items):
        error("Index je mimo rozsah.")
        return

    target = items[index]
    if not confirm(f"Smazat '{target.get('name', '')}' ({target.get('date', '')})?"):
        info("Zrušeno.")
        return

    items.pop(index)
    data["tasks"] = items
    save_data(data)
    info("Úkol smazán.")


def purge_tasks() -> None:
    data = load_data()
    cleaned = sort_tasks(purge_old(data["tasks"]))
    data["tasks"] = cleaned
    save_data(data)
    info(f"Hotovo. Zůstalo {Colors.BOLD}{len(cleaned)}{Colors.RESET}{Colors.GREEN} úkolů.{Colors.RESET}")


def list_tasks() -> None:
    items = sort_tasks(purge_old(load_data()["tasks"]))
    if not items:
        warn("Žádné aktuální úkoly.")
        return

    heading("Aktuální úkoly")
    for i, item in enumerate(items):
        print(
            f"  {Colors.CYAN}{i}{Colors.RESET}: "
            f"{Colors.BOLD}{item.get('name', '')}{Colors.RESET} | "
            f"{Colors.DIM}{item.get('date', '')}{Colors.RESET} | "
            f"{item.get('task', '')}"
        )


def schedule_item_week_type(item: dict) -> str:
    legacy_week = parse_week(str(item.get("week", "")))
    if legacy_week == -1:
        return "odd"
    if legacy_week == 1:
        return "even"
    return parse_week_type(str(item.get("weekType", item.get("week_type", "")))) or "both"


def find_schedule_item(
    items: list[dict],
    date_str: str,
    hour: int,
    group: str | None = None,
) -> tuple[int, dict] | None:
    normalized_group = group.strip().lower() if group else None
    for index, item in enumerate(items):
        hour_raw = str(item.get("hour", "")).strip()
        item_hour = int(hour_raw) if hour_raw.isdigit() else -1
        item_group = str(item.get("group", "Celá")).strip().lower()
        if str(item.get("date", "")).strip() == date_str and item_hour == hour:
            if normalized_group is None or item_group == normalized_group:
                return index, item
    return None


def find_schedule_slot(items: list[dict], date_str: str, hour: int) -> list[tuple[int, dict]]:
    matches: list[tuple[int, dict]] = []
    for index, item in enumerate(items):
        hour_raw = str(item.get("hour", "")).strip()
        item_hour = int(hour_raw) if hour_raw.isdigit() else -1
        if str(item.get("date", "")).strip() == date_str and item_hour == hour:
            matches.append((index, item))
    return matches


def read_schedule_slot() -> tuple[list[date], int] | None:
    date_input = ask("Datum nebo rozmezí (dd.mm.yyyy nebo dd.mm.yyyy-dd.mm.yyyy): ")
    dates = parse_date_or_range(date_input)
    if not dates:
        error("Neplatné datum nebo rozmezí.")
        return None

    hour_value = ask("Kolikátá hodina v dni: ")
    if not hour_value.isdigit():
        error("Hodina musí být číslo.")
        return None

    hour = int(hour_value)
    if hour <= 0:
        error("Hodina musí být větší než 0.")
        return None

    return dates, hour


def add_lesson() -> None:
    data = load_data()
    heading("Přidat hodinu")
    slot = read_schedule_slot()
    if slot is None:
        return

    dates, hour = slot
    subject = ask("Předmět: ")
    classroom = ask("Třída: ")
    teacher = ask("Vyučující: ")
    group = ask("Skupina (Celá, Aj1, Aj2, TvD, TvCh, Šj, Nj, Fj_T): ") or "Celá"

    for dt in dates:
        entry = {
            "date": format_date(dt),
            "day": dt.weekday() + 1,
            "hour": hour,
            "subject": subject,
            "classroom": classroom,
            "teacher": teacher,
            "group": group,
        }
        data["schedule"].append(entry)

    save_data(data)
    info(f"Uloženo pro {Colors.BOLD}{len(dates)}{Colors.RESET}{Colors.GREEN} dny/dní.{Colors.RESET}")


def add_substitution() -> None:
    data = load_data()
    heading("Suplování")
    slot = read_schedule_slot()
    if slot is None:
        return

    dates, hour = slot

    for dt in dates:
        date_str = format_date(dt)
        found_items = find_schedule_slot(data["schedule"], date_str, hour)
        if not found_items:
            warn(f"Hodina dne {date_str} neexistuje, přeskakuji.")
            continue

        if len(found_items) == 1:
            index, item = found_items[0]
        else:
            warn(f"V hodině dne {date_str} existuje více skupin.")
            for i, (_, item_obj) in enumerate(found_items):
                print(
                    f"  {Colors.CYAN}{i}{Colors.RESET}: {item_obj.get('group', 'Celá')} | "
                    f"{item_obj.get('subject', '')} | {item_obj.get('teacher', '')} | {item_obj.get('classroom', '')}"
                )
            group_sel = ask("Skupina pro suplování: ") or ""
            found = find_schedule_item(data["schedule"], date_str, hour, group_sel)
            if found is None:
                error("Zadaná skupina neexistuje, přeskakuji.")
                continue
            index, item = found

        teacher = ask(f"Nový vyučující [{item.get('teacher', '')}]: ")
        classroom = ask(f"Nová třída [{item.get('classroom', '')}]: ")
        group = ask(f"Nová skupina [{item.get('group', 'Celá')}]: ")

        if teacher:
            item["teacher"] = teacher
        if classroom:
            item["classroom"] = classroom
        if group:
            item["group"] = group

        item["type"] = "substitution"
        data["schedule"][index] = item

    save_data(data)
    info("Suplování uloženo.")


def add_holiday() -> None:
    data = load_data()
    heading("Přidat prázdniny")
    date_input = ask("Datum nebo rozmezí (dd.mm.yyyy nebo dd.mm.yyyy-dd.mm.yyyy): ")
    dates = parse_date_or_range(date_input)
    if not dates:
        error("Neplatné datum nebo rozmezí.")
        return

    note = ask("Název (např. Jarní prázdniny): ") or "Prázdniny"

    for dt in dates:
        data["schedule"].append({
            "date": format_date(dt),
            "day": dt.weekday() + 1,
            "type": "holiday",
            "subject": note,
        })

    save_data(data)
    info(f"Prázdniny uloženy pro {Colors.BOLD}{len(dates)}{Colors.RESET}{Colors.GREEN} dny/dní.{Colors.RESET}")


def add_excursion() -> None:
    data = load_data()
    heading("Přidat exkurzi/výlet")
    date_input = ask("Datum nebo rozmezí (dd.mm.yyyy nebo dd.mm.yyyy-dd.mm.yyyy): ")
    dates = parse_date_or_range(date_input)
    if not dates:
        error("Neplatné datum nebo rozmezí.")
        return

    hour_value = ask("Hodina (nebo Enter pro celý den): ")
    hour = int(hour_value) if hour_value.isdigit() else 0
    name = ask("Název: ")

    for dt in dates:
        data["schedule"].append({
            "date": format_date(dt),
            "day": dt.weekday() + 1,
            "hour": hour,
            "type": "excursion",
            "subject": name,
            "group": "Celá",
        })

    save_data(data)
    info(f"Exkurze uložena pro {Colors.BOLD}{len(dates)}{Colors.RESET}{Colors.GREEN} dny/dní.{Colors.RESET}")


def add_free() -> None:
    data = load_data()
    heading("Přidat volno")
    slot = read_schedule_slot()
    if slot is None:
        return

    dates, hour = slot
    group = ask("Skupina (Celá, Aj1, Aj2, TvD, TvCh, Šj, Nj, Fj_T): ") or "Celá"

    for dt in dates:
        entry = {
            "date": format_date(dt),
            "day": dt.weekday() + 1,
            "hour": hour,
            "type": "free",
            "subject": "Volno",
            "group": group,
        }
        data["schedule"].append(entry)

    save_data(data)
    info(f"Volno uloženo pro {Colors.BOLD}{len(dates)}{Colors.RESET}{Colors.GREEN} dny/dní.{Colors.RESET}")


def delete_schedule() -> None:
    data = load_data()
    items = sort_schedule(data["schedule"])

    if not items:
        warn("Žádné hodiny k odstranění.")
        return

    heading("Smazat hodinu")
    for i, item in enumerate(items):
        item_type = item.get("type", "")
        type_label = TYPE_NAMES.get(item_type, "")
        type_str = f" [{Colors.RED}{type_label}{Colors.RESET}]" if type_label else ""
        date_str = item.get("date", format_day(item.get("day", "")))
        print(
            f"  {Colors.CYAN}{i}{Colors.RESET}: {date_str} | Hodina: {item.get('hour', '')} | "
            f"{item.get('subject', '')}{type_str} | {item.get('classroom', '')} | "
            f"{item.get('teacher', '')} | {item.get('group', 'Celá')}"
        )

    index_value = ask("Smazat index: ")
    try:
        index = int(index_value)
    except ValueError:
        error("Index musí být číslo.")
        return

    if index < 0 or index >= len(items):
        error("Index je mimo rozsah.")
        return

    target = items[index]
    target_type = TYPE_NAMES.get(target.get("type", ""), "")
    label = target.get("subject", "") or target_type
    if not confirm(f"Smazat '{label}' ({target.get('date', '')})?"):
        info("Zrušeno.")
        return

    items.pop(index)
    data["schedule"] = items
    save_data(data)
    info("Hodina smazána.")


def list_schedule() -> None:
    items = sort_schedule(load_data()["schedule"])
    if not items:
        warn("Žádný rozvrh.")
        return

    heading("Rozvrh")
    for i, item in enumerate(items):
        item_type = item.get("type", "")
        type_label = TYPE_NAMES.get(item_type, "")
        type_str = f" [{Colors.MAGENTA}{type_label}{Colors.RESET}]" if type_label else ""
        date_str = item.get("date", format_day(item.get("day", "")))
        print(
            f"  {Colors.CYAN}{i}{Colors.RESET}: {date_str} | Hodina: {item.get('hour', '')} | "
            f"{item.get('subject', '')}{type_str} | {item.get('classroom', '')} | "
            f"{item.get('teacher', '')} | {item.get('group', 'Celá')}"
        )


def edit_lesson() -> None:
    data = load_data()
    items = sort_schedule(data["schedule"])

    if not items:
        warn("Žádné hodiny k úpravě.")
        return

    heading("Upravit hodinu")
    for i, item in enumerate(items):
        item_type = item.get("type", "")
        type_label = TYPE_NAMES.get(item_type, "")
        type_str = f" [{Colors.MAGENTA}{type_label}{Colors.RESET}]" if type_label else ""
        date_str = item.get("date", format_day(item.get("day", "")))
        print(
            f"  {Colors.CYAN}{i}{Colors.RESET}: {date_str} | Hodina: {item.get('hour', '')} | "
            f"{item.get('subject', '')}{type_str} | {item.get('classroom', '')} | "
            f"{item.get('teacher', '')} | {item.get('group', 'Celá')}"
        )

    index_value = ask("Upravit index: ")
    try:
        index = int(index_value)
    except ValueError:
        error("Index musí být číslo.")
        return

    if index < 0 or index >= len(items):
        error("Index je mimo rozsah.")
        return

    item = items[index]
    original = dict(item)

    print(f"\n{Colors.DIM}Aktuální hodnoty (Enter = ponechat):{Colors.RESET}")
    print(f"  Předmět:   {item.get('subject', '')}")
    print(f"  Učitel:    {item.get('teacher', '')}")
    print(f"  Třída:     {item.get('classroom', '')}")
    print(f"  Skupina:   {item.get('group', 'Celá')}")
    print(f"  Den:       {item.get('day', '')} ({format_day(item.get('day', ''))})")
    print(f"  Hodina:    {item.get('hour', '')}")

    subject = ask(f"Nový předmět [{item.get('subject', '')}]: ")
    teacher = ask(f"Nový učitel [{item.get('teacher', '')}]: ")
    classroom = ask(f"Nová třída [{item.get('classroom', '')}]: ")
    group = ask(f"Nová skupina [{item.get('group', 'Celá')}]: ")
    day = ask(f"Nový den (1-7) [{item.get('day', '')}]: ")
    hour = ask(f"Nová hodina [{item.get('hour', '')}]: ")

    if subject:
        item["subject"] = subject
    if teacher:
        item["teacher"] = teacher
    if classroom:
        item["classroom"] = classroom
    if group:
        item["group"] = group
    if day and day.isdigit() and 1 <= int(day) <= 7:
        item["day"] = int(day)
    if hour and hour.isdigit() and int(hour) > 0:
        item["hour"] = int(hour)

    if item == original:
        info("Žádné změny.")
        return

    data["schedule"] = items
    save_data(data)
    info("Hodina upravena.")


def schedule_menu() -> None:
    heading("Rozvrh – menu")
    print(f"  {Colors.CYAN}A{Colors.RESET} = přidat hodinu")
    print(f"  {Colors.CYAN}U{Colors.RESET} = upravit hodinu")
    print(f"  {Colors.CYAN}S{Colors.RESET} = suplování")
    print(f"  {Colors.CYAN}V{Colors.RESET} = volno")
    print(f"  {Colors.CYAN}P{Colors.RESET} = prázdniny")
    print(f"  {Colors.CYAN}E{Colors.RESET} = exkurze/výlet")
    print(f"  {Colors.CYAN}D{Colors.RESET} = smazat hodinu")
    print(f"  {Colors.CYAN}L{Colors.RESET} = vypsat hodiny")
    choice = ask("> ").lower()

    if choice == "a":
        add_lesson()
    elif choice == "u":
        edit_lesson()
    elif choice == "s":
        add_substitution()
    elif choice == "v":
        add_free()
    elif choice == "p":
        add_holiday()
    elif choice == "e":
        add_excursion()
    elif choice == "d":
        delete_schedule()
    elif choice == "l":
        list_schedule()
    else:
        error("Neznámá volba.")


def menu() -> None:
    heading("Inženýři – správa dat")
    print(f"  {Colors.CYAN}A{Colors.RESET} = přidat úkol")
    print(f"  {Colors.CYAN}D{Colors.RESET} = smazat úkol")
    print(f"  {Colors.CYAN}P{Colors.RESET} = promazat staré")
    print(f"  {Colors.CYAN}L{Colors.RESET} = vypsat úkoly")
    print(f"  {Colors.CYAN}R{Colors.RESET} = rozvrh")
    choice = ask("> ").lower()

    if choice == "a":
        add_task()
    elif choice == "d":
        delete_task()
    elif choice == "p":
        purge_tasks()
    elif choice == "l":
        list_tasks()
    elif choice == "r":
        schedule_menu()
    else:
        error("Neznámá volba.")


def main() -> None:
    try:
        command = sys.argv[1].lower() if len(sys.argv) > 1 else ""

        if command in {"a", "add"}:
            add_task()
        elif command in {"d", "del", "delete"}:
            delete_task(sys.argv[2] if len(sys.argv) > 2 else None)
        elif command in {"p", "purge"}:
            purge_tasks()
        elif command in {"l", "list"}:
            list_tasks()
        elif command in {"r", "rozvrh", "schedule"}:
            schedule_menu()
        elif command in {"u", "edit"}:
            edit_lesson()
        elif command in {"h", "help", "-h", "--help"}:
            heading("Nápověda")
            print("  Příkazy:")
            print(f"    {Colors.CYAN}a{Colors.RESET} / {Colors.CYAN}add{Colors.RESET}       – přidat úkol")
            print(f"    {Colors.CYAN}d{Colors.RESET} / {Colors.CYAN}del{Colors.RESET}       – smazat úkol (volitelně index)")
            print(f"    {Colors.CYAN}p{Colors.RESET} / {Colors.CYAN}purge{Colors.RESET}     – promazat staré úkoly")
            print(f"    {Colors.CYAN}l{Colors.RESET} / {Colors.CYAN}list{Colors.RESET}      – vypsat úkoly")
            print(f"    {Colors.CYAN}r{Colors.RESET} / {Colors.CYAN}rozvrh{Colors.RESET}    – správa rozvrhu")
            print(f"    {Colors.CYAN}u{Colors.RESET} / {Colors.CYAN}edit{Colors.RESET}      – upravit hodinu v rozvrhu")
            print(f"    {Colors.CYAN}h{Colors.RESET} / {Colors.CYAN}help{Colors.RESET}      – tato nápověda")
        elif command:
            error(f"Neznámý příkaz: {command}")
            print(f"  Použijte {Colors.CYAN}h{Colors.RESET} pro nápovědu.")
        else:
            menu()
    except KeyboardInterrupt:
        print()
    except EOFError:
        pass


if __name__ == "__main__":
    main()
