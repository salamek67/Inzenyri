#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from datetime import date, datetime
from pathlib import Path


FILE = Path(__file__).with_name("data.js")
PREFIX = "var data = "


def parse_date(value: str) -> date | None:
    try:
        return datetime.strptime(value, "%d.%m.%Y").date()
    except ValueError:
        return None


def today() -> date:
    return date.today()


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


def load_data() -> list[dict]:
    if not FILE.exists():
        return []

    text = FILE.read_text(encoding="utf-8").strip()
    if not text:
        return []

    match = re.search(r"var\s+data\s*=\s*(\[[\s\S]*\])\s*;?\s*$", text)
    if not match:
        raise ValueError("Soubor data.js nemá očekávaný formát.")

    data = json.loads(match.group(1))
    if not isinstance(data, list):
        raise ValueError("data.js musí obsahovat pole objektů.")
    return data


def save_data(items: list[dict]) -> None:
    items = sort_tasks(purge_old(items))
    payload = json.dumps(items, ensure_ascii=False, indent=2)
    FILE.write_text(f"{PREFIX}{payload};\n", encoding="utf-8")


def ask(prompt: str) -> str:
    return input(prompt).strip()


def add_task() -> None:
    items = sort_tasks(purge_old(load_data()))

    name = ask("Název: ")
    date_value = ask("Datum (dd.mm.yyyy): ")
    task = ask("Úkol: ")
    solution = ask("Řešení: ")

    if parse_date(date_value) is None:
        print("Neplatné datum.")
        return

    items.append({
        "name": name,
        "date": date_value,
        "task": task,
        "solution": solution,
    })
    save_data(items)
    print("Úkol uložen.")


def delete_task(index_value: str | None = None) -> None:
    items = sort_tasks(purge_old(load_data()))

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
    save_data(items)
    print("Úkol smazán.")


def purge_tasks() -> None:
    items = load_data()
    cleaned = sort_tasks(purge_old(items))
    save_data(cleaned)
    print(f"Hotovo. Zůstalo {len(cleaned)} úkolů.")


def list_tasks() -> None:
    items = sort_tasks(purge_old(load_data()))
    if not items:
        print("Žádné aktuální úkoly.")
        return

    for i, item in enumerate(items):
        print(f"{i}: {item.get('name', '')} | {item.get('date', '')} | {item.get('task', '')}")


def menu() -> None:
    print("A = přidat, D = smazat, P = promazat staré, L = vypsat")
    choice = ask("> ").lower()

    if choice == "a":
        add_task()
    elif choice == "d":
        delete_task()
    elif choice == "p":
        purge_tasks()
    elif choice == "l":
        list_tasks()
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
    else:
        menu()


if __name__ == "__main__":
    main()
