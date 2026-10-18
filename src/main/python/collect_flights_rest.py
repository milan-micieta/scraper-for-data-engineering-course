#!/usr/bin/env python3
"""
Zbiera predané letenky z REST endpointu /api/v1/events.

- Stránkuje cez after=next_after, kým has_more=true.
- Kurzor (next_after) ukladá až PO zápise dát.
- Deduplikuje podľa event_id.
- Po dobehnutí spí 15 minút a pýta sa znova.
"""

import json
import sys
import time
from pathlib import Path

import requests

from common import BASE_URL, atomic_write_json, append_jsonl, read_json

# ---------------------------------------------------------------------------
# KONFIGURÁCIA
# ---------------------------------------------------------------------------
STATE_FILE = Path("flights_rest_state.json")   # ukladá next_after + seen_ids (len hashe)
OUTPUT_FILE = Path("flights_events.jsonl")
SEEN_IDS_FILE = Path("flights_seen_ids.json")

URL = f"{BASE_URL}/api/v1/events"
LIMIT = 5000
POLL_INTERVAL = 15 * 60  # 15 minút

# Maximálny počet seen_ids, ktoré držíme v pamäti (aby súbor nerástol donekonečna).
# Staršie eventy už nikdy neprídu, takže ich môžeme zabudnúť.
MAX_SEEN_IDS = 200_000


def load_state() -> tuple[int, set[str]]:
    state = read_json(STATE_FILE, default={"after": 0})
    seen = set(read_json(SEEN_IDS_FILE, default=[]))
    return state.get("after", 0), seen


def save_cursor(after: int) -> None:
    atomic_write_json(STATE_FILE, {"after": after})


def save_seen_ids(seen: set[str]) -> None:
    # Ak je toho priveľa, orežeme (nemusí byť dokonalé, ale stačí).
    if len(seen) > MAX_SEEN_IDS:
        seen = set(list(seen)[-MAX_SEEN_IDS:])
    atomic_write_json(SEEN_IDS_FILE, list(seen))


def fetch_page(after: int) -> dict:
    params = {"after": after, "limit": LIMIT, "type": "ticket.sold"}
    r = requests.get(URL, params=params, timeout=60)
    r.raise_for_status()
    return r.json()


def process_page(data: dict, seen: set[str]) -> tuple[int, int]:
    """
    Spracuje jednu stránku. Vráti (next_after, počet_nových).
    DÔLEŽITÉ: zapisujeme dáta, až potom vraciame next_after – volajúci
    ho uloží až po úspešnom zápise.
    """
    events = data.get("events", [])
    new_count = 0

    for ev in events:
        event_id = ev.get("event_id")
        if not event_id:
            continue
        if event_id in seen:
            continue

        # 1) Zapíš dáta na disk
        append_jsonl(OUTPUT_FILE, ev)
        new_count += 1

        # 2) Teprve potom označ ako videné
        seen.add(event_id)

    # Ulož seen_ids (môže byť priebežne, nie je to kurzor)
    if new_count:
        save_seen_ids(seen)

    return data.get("next_after", after_placeholder(data)), new_count


def after_placeholder(data: dict) -> int:
    """Fallback, ak next_after chýba – použije posledný pub_seq."""
    events = data.get("events", [])
    if not events:
        return 0
    return max(ev.get("pub_seq", 0) for ev in events)


def main() -> int:
    after, seen = load_state()
    print(f"[INFO] Štart. after={after}, videných event_id={len(seen)}")
    print(f"[INFO] Výstup: {OUTPUT_FILE}, stav: {STATE_FILE}")

    while True:
        try:
            data = fetch_page(after)
        except requests.RequestException as e:
            print(f"[ERROR] REST request zlyhal: {e}. Skúšam znova o 60 s.")
            time.sleep(60)
            continue

        new_after, new_count = process_page(data, seen)
        has_more = data.get("has_more", False)

        # Kurzor uložíme AŽ TERAZ – po úspešnom zápise všetkých eventov
        if new_count or new_after != after:
            save_cursor(new_after)
        after = new_after

        print(f"[OK] after={after} nových={new_count} has_more={has_more}")

        if not has_more:
            print(f"[INFO] Spím {POLL_INTERVAL // 60} min...")
            time.sleep(POLL_INTERVAL)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[INFO] Ukončené používateľom.")
        sys.exit(0)