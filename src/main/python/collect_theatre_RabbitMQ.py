#!/usr/bin/env python3
"""
Zbiera predaje divadelných lístkov z RabbitMQ fronty theatre.sales.<team>.

- Deduplikuje podľa event_id (at-least-once doručenie).
- Ukladá správy do JSONL súboru (jeden JSON objekt na riadok).
- Acknowledge až po úspešnom zápise na disk.
- Po reštarte pokračuje tam, kde skončil (stav dedup/save drží v súbore).
"""

import json
import os
import signal
import sys
from pathlib import Path

import pika

# ---------------------------------------------------------------------------
# KONFIGURÁCIA – upravte podľa potreby
# ---------------------------------------------------------------------------
TEAM = "team9"
QUEUE = f"theatre.sales.{TEAM}"

RABBIT_HOST = "158.180.46.49"
RABBIT_PORT = 5672
RABBIT_USER = "reader"
RABBIT_PASSWORD = "e0d4da6d775a596395ef4aebb8e95400"
RABBIT_VHOST = "/"

OUTPUT_FILE = Path(f"theatre_sales_{TEAM}.jsonl")
SEEN_IDS_FILE = Path(f"theatre_sales_{TEAM}_seen_ids.txt")

# ---------------------------------------------------------------------------
# DEDUPLIKÁCIA
# ---------------------------------------------------------------------------
def load_seen_ids(path: Path) -> set[str]:
    """Načíta už videné event_id zo súboru (jeden na riadok)."""
    if not path.exists():
        return set()
    with path.open("r", encoding="utf-8") as f:
        return {line.strip() for line in f if line.strip()}


def save_seen_id(path: Path, event_id: str) -> None:
    """Pridá event_id do perzistentného zoznamu."""
    with path.open("a", encoding="utf-8") as f:
        f.write(event_id + "\n")


# ---------------------------------------------------------------------------
# SPRACOVANIE SPRÁVY
# ---------------------------------------------------------------------------
def make_on_message(seen_ids: set[str], out_fh):
    def on_message(channel, method, properties, body):
        try:
            sale = json.loads(body)
        except json.JSONDecodeError as e:
            print(f"[WARN] Neplatný JSON, zahadzujem: {e}", file=sys.stderr)
            # Musíme acknúť, inak sa správa vráti do fronty stále dokola.
            channel.basic_ack(method.delivery_tag)
            return

        event_id = sale.get("event_id")
        if not event_id:
            print(f"[WARN] Správa bez event_id, zahadzujem: {sale}", file=sys.stderr)
            channel.basic_ack(method.delivery_tag)
            return

        # Deduplikácia
        if event_id in seen_ids:
            print(f"[SKIP] Duplikát: {event_id}")
            channel.basic_ack(method.delivery_tag)
            return

        # Zápis na disk (flush, aby sme mali istotu, že je to bezpečne uložené)
        out_fh.write(json.dumps(sale, ensure_ascii=False) + "\n")
        out_fh.flush()
        os.fsync(out_fh.fileno())

        # Až teraz ack – po bezpečnom uložení
        channel.basic_ack(method.delivery_tag)

        # Aktualizuj dedup stav
        seen_ids.add(event_id)
        save_seen_id(SEEN_IDS_FILE, event_id)

        price = sale.get("price")
        print(
            f"[OK] {sale.get('sold_at', '?')} | "
            f"{sale.get('performance', '?')} | "
            f"{event_id} | price={price}"
        )

    return on_message


# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------
def main() -> int:
    seen_ids = load_seen_ids(SEEN_IDS_FILE)
    print(f"Načítaných {len(seen_ids)} videných event_id z {SEEN_IDS_FILE}")
    print(f"Fronta: {QUEUE}")
    print(f"Výstup: {OUTPUT_FILE}")

    credentials = pika.PlainCredentials(RABBIT_USER, RABBIT_PASSWORD)
    parameters = pika.ConnectionParameters(
        host=RABBIT_HOST,
        port=RABBIT_PORT,
        virtual_host=RABBIT_VHOST,
        credentials=credentials,
        heartbeat=60,
        blocked_connection_timeout=300,
    )

    connection = pika.BlockingConnection(parameters)
    channel = connection.channel()

    # Queue už existuje – NEdeclarujeme ju (reader nemá právo).
    # Obmedzíme prefetch, aby sme nezaplavili pamäť.
    channel.basic_qos(prefetch_count=10)

    out_fh = OUTPUT_FILE.open("a", encoding="utf-8")

    # Graceful shutdown na Ctrl+C
    stopping = {"flag": False}

    def handle_sigint(signum, frame):
        if not stopping["flag"]:
            stopping["flag"] = True
            print("\n[INFO] Zastavujem... čakám na dokončenie aktuálnej správy.")
            try:
                channel.stop_consuming()
            except Exception:
                pass

    signal.signal(signal.SIGINT, handle_sigint)
    signal.signal(signal.SIGTERM, handle_sigint)

    channel.basic_consume(
        queue=QUEUE,
        on_message_callback=make_on_message(seen_ids, out_fh),
        auto_ack=False,  # explicit ack
    )

    print("[INFO] Čakám na správy. Ukončite Ctrl+C.")
    try:
        channel.start_consuming()
    finally:
        try:
            channel.close()
        except Exception:
            pass
        try:
            connection.close()
        except Exception:
            pass
        out_fh.close()
        print("[INFO] Hotovo.")

    return 0


if __name__ == "__main__":
    sys.exit(main())