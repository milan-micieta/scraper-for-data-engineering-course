#!/usr/bin/env python3
"""
Zbiera zmeny predstavení z SSE streamu /api/v1/theatre/stream.

- Server posiela najprv celú históriu, potom live zmeny.
- Pri reconnecte posielame Last-Event-ID, aby sme dostali len chýbajúce.
- Deduplikuje podľa event_id.
- last_id ukladá až PO zápise eventu.
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
STATE_FILE = Path("theatre_sse_state.json")      # {"last_id": int}
OUTPUT_FILE = Path("theatre_events.jsonl")
SEEN_IDS_FILE = Path("theatre_seen_ids.json")

URL = f"{BASE_URL}/api/v1/theatre/stream"
RECONNECT_DELAY = 5       # sekundy pred reconnectom
MAX_SEEN_IDS = 200_000


def load_state() -> tuple[int, set[str]]:
    state = read_json(STATE_FILE, default={"last_id": 0})
    seen = set(read_json(SEEN_IDS_FILE, default=[]))
    return state.get("last_id", 0), seen


def save_last_id(last_id: int) -> None:
    atomic_write_json(STATE_FILE, {"last_id": last_id})


def save_seen_ids(seen: set[str]) -> None:
    if len(seen) > MAX_SEEN_IDS:
        seen = set(list(seen)[-MAX_SEEN_IDS:])
    atomic_write_json(SEEN_IDS_FILE, list(seen))


def parse_sse_stream(resp, seen: set[str], last_id: int) -> int:
    """
    Číta SSE stream po riadkoch. Vráti nové last_id.
    Formát:
        event: performance.added
        id: 12345
        data: {...json...}
        <prázdny riadok>
    """
    current_event_type = None
    current_id = None
    data_lines: list[str] = []

    for raw_line in resp.iter_lines(decode_unicode=True):
        if raw_line is None:
            continue
        line = raw_line.rstrip("\r")

        # Prázdny riadok = koniec správy
        if line == "":
            if data_lines:
                handle_message(current_event_type, current_id, "\n".join(data_lines),
                               seen)
                # Po spracovaní aktualizuj last_id (až po zápise!)
                if current_id is not None:
                    last_id = int(current_id)
                    save_last_id(last_id)
            current_event_type = None
            current_id = None
            data_lines = []
            continue

        # Komentár / ping – ignoruj
        if line.startswith(":"):
            continue

        if line.startswith("event:"):
            current_event_type = line[len("event:"):].strip()
        elif line.startswith("id:"):
            current_id = line[len("id:"):].strip()
        elif line.startswith("data:"):
            data_lines.append(line[len("data:"):].lstrip())

    return last_id


def handle_message(event_type: str | None, sse_id: str | None,
                   data_str: str, seen: set[str]) -> None:
    try:
        ev = json.loads(data_str)
    except json.JSONDecodeError as e:
        print(f"[WARN] Neplatný JSON v SSE: {e}", file=sys.stderr)
        return

    event_id = ev.get("event_id")
    if not event_id:
        print(f"[WARN] SSE bez event_id: {ev}", file=sys.stderr)
        return

    if event_id in seen:
        return

    # 1) Zapíš dáta
    append_jsonl(OUTPUT_FILE, ev)
    # 2) Označ ako videné
    seen.add(event_id)
    save_seen_ids(seen)

    seq = ev.get("stream_seq", "?")
    etype = ev.get("event_type", event_type or "?")
    print(f"[OK] seq={seq} type={etype} id={event_id}")


def main() -> int:
    last_id, seen = load_state()
    print(f"[INFO] Štart SSE. last_id={last_id}, videných={len(seen)}")
    print(f"[INFO] Výstup: {OUTPUT_FILE}, stav: {STATE_FILE}")

    while True:
        headers = {"Accept": "text/event-stream"}
        if last_id:
            headers["Last-Event-ID"] = str(last_id)

        try:
            with requests.get(URL, headers=headers, stream=True, timeout=(10, 300)) as r:
                r.raise_for_status()
                print(f"[INFO] Pripojené (Last-Event-ID={last_id})")
                last_id = parse_sse_stream(r, seen, last_id)
                print(f"[INFO] Stream sa zavrel. last_id={last_id}")
        except requests.RequestException as e:
            print(f"[ERROR] SSE zlyhalo: {e}", file=sys.stderr)

        print(f"[INFO] Reconnect o {RECONNECT_DELAY} s...")
        time.sleep(RECONNECT_DELAY)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[INFO] Ukončené používateľom.")
        sys.exit(0)