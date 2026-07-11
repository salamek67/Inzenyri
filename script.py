#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from datetime import date, datetime
from pathlib import Path


FILE = Path(__file__).with_name("data.js")
PREFIX = "var data = "
DEFAULT_DATA = {"tasks": [], "schedule": []}
TYPE_NAMES = {
    "holiday": "Prázdniny",
    "substitution": "Suplování",
    "excursion": "Exkurze/Výlet",
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


def parse_date(value: str) -> date | None:
    try:
        return datetime.strptime(value, "%d.%m.%Y").date()
    except ValueError:
        return None


def parse_day(value: str) -> int | None:
    raw = str(value).strip().lower().replace(".", "")
    if raw.isdigit():
        day = int(raw)
        if 1 <= day <= 5:
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


def sort_schedule(items: list[dict]) -> list[dict]:
    def sort_key(item: dict) -> tuple[int, int, int, str]:
        week_type = schedule_item_week_type(item)
        week_order = {"both": 0, "odd": 1, "even": 2}.get(week_type, 0)
        day = parse_day(str(item.get("day", ""))) or 99
        item_type = item.get("type", "")
        type_order = {"holiday": 0, "excursion": 1, "substitution": 2, "": 3}.get(item_type, 3)
        hour_raw = str(item.get("hour", "")).strip()
        hour = int(hour_raw) if hour_raw.isdigit() else 0
        return (type_order, day, hour, str(item.get("subject", "")))

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
    normalized["schedule"] = sort_schedule(normalized["schedule"])
    payload = json.dumps(normalized, ensure_ascii=False, indent=2)
    FILE.write_text(f"{PREFIX}{payload};\n", encoding="utf-8")


def ask(prompt: str) -> str:
    return input(prompt).strip()


def add_task() -> None:
    data = load_data()

    name = ask("Název: ")
    date_value = ask("Datum (dd.mm.yyyy): ")
    task = ask("Úkol: ")
    solution = ask("Řešení: ")

    if parse_date(date_value) is None:
        print("Neplatné datum.")
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
    print("Úkol uložen.")


def delete_task(index_value: str | None = None) -> None:
    data = load_data()
    items = sort_tasks(purge_old(data["tasks"]))

    if not items:
        print("Žádné úkoly k odstranění.")
        return

    for i, item in enumerate(items):
        print(f"{i}: {item.get('name', '')} [{item.get('date', '')}]")

    if index_value is None:
        index_value = ask("Smazat index: ")

    try:
        index = int(index_value)
    except ValueError:
        print("Index musí být číslo.")
        return

    if index < 0 or index >= len(items):
        print("Index je mimo rozsah.")
        return

    items.pop(index)
    data["tasks"] = items
    save_data(data)
    print("Úkol smazán.")


def purge_tasks() -> None:
    data = load_data()
    cleaned = sort_tasks(purge_old(data["tasks"]))
    data["tasks"] = cleaned
    save_data(data)
    print(f"Hotovo. Zůstalo {len(cleaned)} úkolů.")


def list_tasks() -> None:
    items = sort_tasks(purge_old(load_data()["tasks"]))
    if not items:
        print("Žádné aktuální úkoly.")
        return

    for i, item in enumerate(items):
        print(f"{i}: {item.get('name', '')} | {item.get('date', '')} | {item.get('task', '')}")


def schedule_item_week_type(item: dict) -> str:
    legacy_week = parse_week(str(item.get("week", "")))
    if legacy_week == -1:
        return "odd"
    if legacy_week == 1:
        return "even"
    return parse_week_type(str(item.get("weekType", item.get("week_type", "")))) or "both"


def find_schedule_item(
    items: list[dict],
    day: int,
    hour: int,
    group: str | None = None,
    week_type: str | None = None,
) -> tuple[int, dict] | None:
    normalized_group = group.strip().lower() if group else None
    normalized_week_type = parse_week_type(week_type or "") if week_type is not None else None
    for index, item in enumerate(items):
        hour_raw = str(item.get("hour", "")).strip()
        item_hour = int(hour_raw) if hour_raw.isdigit() else -1
        item_group = str(item.get("group", "Celá")).strip().lower()
        item_week_type = schedule_item_week_type(item)
        if parse_day(str(item.get("day", ""))) == day and item_hour == hour:
            if normalized_week_type is not None and item_week_type != normalized_week_type:
                continue
            if normalized_group is None or item_group == normalized_group:
                return index, item
    return None


def find_schedule_slot(items: list[dict], day: int, hour: int, week_type: str | None = None) -> list[tuple[int, dict]]:
    matches: list[tuple[int, dict]] = []
    normalized_week_type = parse_week_type(week_type or "") if week_type is not None else None
    for index, item in enumerate(items):
        hour_raw = str(item.get("hour", "")).strip()
        item_hour = int(hour_raw) if hour_raw.isdigit() else -1
        item_week_type = schedule_item_week_type(item)
        if parse_day(str(item.get("day", ""))) == day and item_hour == hour and (normalized_week_type is None or item_week_type == normalized_week_type):
            matches.append((index, item))
    return matches


def read_schedule_slot() -> tuple[int, int, str] | None:
    day_value = ask("Den (1-5 nebo Po, Út, St, Čt, Pá): ")
    day = parse_day(day_value)
    if day is None:
        print("Neplatný den.")
        return None

    hour_value = ask("Kolikátá hodina v dni: ")
    if not hour_value.isdigit():
        print("Hodina musí být číslo.")
        return None

    hour = int(hour_value)
    if hour <= 0:
        print("Hodina musí být větší než 0.")
        return None
    week_value = ask("Týden (O obě, L lichý, S sudý): ") or "O"
    week_type = parse_week_type(week_value)
    if week_type is None:
        print("Neplatný typ týdne.")
        return None

    return day, hour, week_type


def add_lesson() -> None:
    data = load_data()
    slot = read_schedule_slot()
    if slot is None:
        return

    day, hour, week_type = slot
    subject = ask("Předmět: ")
    classroom = ask("Třída: ")
    teacher = ask("Vyučující: ")
    group = ask("Skupina (Celá, Aj1, Aj2, TvD, TvCh, Šj, Nj, Fj_T): ") or "Celá"

    entry = {
        "day": day,
        "hour": hour,
        "weekType": week_type,
        "subject": subject,
        "classroom": classroom,
        "teacher": teacher,
        "group": group,
    }

    data["schedule"].append(entry)

    save_data(data)
    print("Hodina uložená.")


def add_substitution() -> None:
    data = load_data()
    slot = read_schedule_slot()
    if slot is None:
        return

    day, hour, week_type = slot
    found_items = find_schedule_slot(data["schedule"], day, hour, week_type)
    if not found_items:
        print("Tato hodina v rozvrhu neexistuje.")
        return

    if len(found_items) == 1:
        index, item = found_items[0]
        print(
            f"Nalezeno: {format_schedule_week_type(item)} | {item.get('group', 'Celá')} | "
            f"{item.get('subject', '')} | {item.get('teacher', '')} | {item.get('classroom', '')}"
        )
    else:
        print("V této hodině existuje více skupin.")
        for i, (_, item) in enumerate(found_items):
            print(
                f"{i}: {format_schedule_week_type(item)} | {item.get('group', 'Celá')} | "
                f"{item.get('subject', '')} | {item.get('teacher', '')} | {item.get('classroom', '')}"
            )

        group = ask("Skupina pro suplování: ") or ""
        found = find_schedule_item(data["schedule"], day, hour, group, week_type)
        if found is None:
            print("Zadaná skupina v této hodině neexistuje.")
            return
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

    existing_type = item.get("type", "")
    if existing_type and existing_type != "substitution":
        confirm = ask(f"Tato hodina má typ '{TYPE_NAMES.get(existing_type, existing_type)}'. Přepsat na suplování? (a/n): ")
        if confirm.lower() != "a":
            print("Zrušeno.")
            return
    item["type"] = "substitution"
    data["schedule"][index] = item
    save_data(data)
    print("Suplování uloženo.")


def add_holiday() -> None:
    data = load_data()
    day_value = ask("Den (1-5 nebo název): ")
    day = parse_day(day_value)
    if day is None:
        print("Neplatný den.")
        return
    print(f"  {week_parity_hint(0)}")
    print(f"  {week_parity_hint(1)}")
    week_value = ask("Týden (O obě, L lichý, S sudý, Enter = obě): ") or "O"
    week_type = parse_week_type(week_value)
    if week_type is None:
        print("Neplatný typ týdne.")
        return
    note = ask("Název (např. Jarní prázdniny): ")
    data["schedule"].append({
        "day": day,
        "type": "holiday",
        "weekType": week_type,
        "subject": note or "Prázdniny",
    })
    save_data(data)
    print("Prázdniny uloženy.")


def add_excursion() -> None:
    data = load_data()
    day_value = ask("Den (1-5 nebo název): ")
    day = parse_day(day_value)
    if day is None:
        print("Neplatný den.")
        return
    hour_value = ask("Hodina (nebo Enter pro celý den): ")
    hour = int(hour_value) if hour_value.isdigit() else 0
    name = ask("Název: ")
    print(f"  {week_parity_hint(0)}")
    print(f"  {week_parity_hint(1)}")
    week_value = ask("Týden (O obě, L lichý, S sudý): ") or "O"
    week_type = parse_week_type(week_value)
    if week_type is None:
        print("Neplatný typ týdne.")
        return
    data["schedule"].append({
        "day": day,
        "hour": hour,
        "type": "excursion",
        "subject": name,
        "group": "Celá",
        "weekType": week_type,
    })
    save_data(data)
    print("Exkurze uložena.")


def delete_schedule() -> None:
    data = load_data()
    items = sort_schedule(data["schedule"])

    if not items:
        print("Žádné hodiny k odstranění.")
        return

    for i, item in enumerate(items):
        item_type = item.get("type", "")
        type_label = TYPE_NAMES.get(item_type, "")
        type_str = f" [{type_label}]" if type_label else ""
        print(
            f"{i}: {format_schedule_week_type(item)} | {format_day(item.get('day', ''))} | {item.get('hour', '')} | "
            f"{item.get('subject', '')}{type_str} | {item.get('classroom', '')} | "
            f"{item.get('teacher', '')} | {item.get('group', 'Celá')}"
        )

    index_value = ask("Smazat index: ")
    try:
        index = int(index_value)
    except ValueError:
        print("Index musí být číslo.")
        return

    if index < 0 or index >= len(items):
        print("Index je mimo rozsah.")
        return

    items.pop(index)
    data["schedule"] = items
    save_data(data)
    print("Hodina smazána.")


def list_schedule() -> None:
    items = sort_schedule(load_data()["schedule"])
    if not items:
        print("Žádný rozvrh.")
        return

    for i, item in enumerate(items):
        item_type = item.get("type", "")
        type_label = TYPE_NAMES.get(item_type, "")
        type_str = f" [{type_label}]" if type_label else ""
        print(
            f"{i}: {format_schedule_week_type(item)} | {format_day(item.get('day', ''))} | {item.get('hour', '')} | "
            f"{item.get('subject', '')}{type_str} | {item.get('classroom', '')} | "
            f"{item.get('teacher', '')} | {item.get('group', 'Celá')}"
        )


def schedule_menu() -> None:
    print("A = přidat hodinu, S = suplování, P = prázdniny, E = exkurze/výlet, D = smazat hodinu, L = vypsat hodiny")
    choice = ask("> ").lower()

    if choice == "a":
        add_lesson()
    elif choice == "s":
        add_substitution()
    elif choice == "p":
        add_holiday()
    elif choice == "e":
        add_excursion()
    elif choice == "d":
        delete_schedule()
    elif choice == "l":
        list_schedule()
    else:
        print("Neznámá volba.")


def menu() -> None:
    print("A = přidat úkol, D = smazat úkol, P = promazat staré, L = vypsat úkoly, R = rozvrh")
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
        print("Neznámá volba.")


def main() -> None:
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
    else:
        menu()


if __name__ == "__main__":
    main()
