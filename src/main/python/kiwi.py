#!/usr/bin/env python3
"""
Kiwi.com price scraper (Playwright).

Inštalácia:
    pip install playwright
    playwright install chromium

Použitie:
    python kiwi_scraper.py kosice-slovakia london-united-kingdom 2026-11-15
    python kiwi_scraper.py budapest-hungary,vienna-austria,kosice-slovakia london-united-kingdom 2026-11-18
                                                         # viac odletových miest naraz (oddelené čiarkou)
    python kiwi_scraper.py ... --headed                  # zobrazí okno prehliadača (ladenie)
    python kiwi_scraper.py ... --json --no-csv           # režim pre Spring (JSON na stdout)
    python kiwi_scraper.py ... --dump-graphql g.json     # uloží surové GraphQL odpovede (ladenie času odletu)

Slug miest (napr. "kosice-slovakia") skopíruj z URL na kiwi.com po ručnom vyhľadaní.
V režime --json ide na stdout IBA JSON, všetky logy idú na stderr.
"""
import argparse
import csv
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

from playwright.sync_api import sync_playwright, TimeoutError as PWTimeout

CSV_PATH = Path("prices.csv")
CSV_HEADER = ["scraped_at", "origin", "origin_country", "dest", "departure_time", "price", "currency"]


def build_url(origin, dest, depart):
    """Iba jednosmerná cesta, iba priame lety (stopNumber=0~true)."""
    return f"https://www.kiwi.com/en/search/results/{origin}/{dest}/{depart}/no-return?stopNumber=0~true"


def walk(obj):
    """Rekurzívne prejde JSON a vráti všetky dict-y."""
    if isinstance(obj, dict):
        yield obj
        for v in obj.values():
            yield from walk(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from walk(v)


def find_departure(itinerary):
    """Čas odletu (lokálny čas letiska) prvého segmentu itinerára, alebo ''.
    Štruktúra GraphQL odpovede sa môže meniť, preto je to best-effort."""
    # 1. preferovaný tvar: ...segment.source.localTime
    for sub in walk(itinerary):
        src = sub.get("source")
        if isinstance(src, dict) and src.get("localTime"):
            return src["localTime"]
    # 2. akýkoľvek kľúč s lokálnym časom odletu
    for key in ("localTime", "departureTime", "localDeparture", "dTime"):
        for sub in walk(itinerary):
            value = sub.get(key)
            if isinstance(value, str) and re.match(r"\d{4}-\d{2}-\d{2}T", value):
                return value
    return ""


def find_origin(itinerary):
    """Odletové letisko/mesto/krajina prvého segmentu itinerára (best-effort)."""
    for sub in walk(itinerary):
        src = sub.get("source")
        if isinstance(src, dict) and src.get("localTime"):
            station = src.get("station") or {}
            city = station.get("city") or {}
            country = station.get("country") or city.get("country") or {}
            return {
                "code": station.get("code") or "",
                "city": city.get("name") or station.get("name") or "",
                "country": country.get("name") or country.get("code") or "",
            }
    return {"code": "", "city": "", "country": ""}


def extract_from_json(payload):
    """Nájde itineráre s cenou v GraphQL odpovedi (štruktúra sa môže meniť)."""
    found = []
    for d in walk(payload):
        price = d.get("price")
        if isinstance(price, dict) and "amount" in price and (
                "sector" in d or "outbound" in d or "legs" in d or "provider" in d
        ):
            try:
                amount = float(price["amount"])
            except (TypeError, ValueError):
                continue
            origin_info = find_origin(d)
            found.append({
                "price": amount,
                "origin_code": origin_info["code"],
                "origin_city": origin_info["city"],
                "origin_country": origin_info["country"],
                "currency": price.get("currency") or "EUR",
                "id": d.get("id", ""),
                "departure_time": find_departure(d),
                "booking_url": (d.get("bookingOptions") or {}).get("edges", [{}])[0]
                .get("node", {}).get("bookingUrl", "") if isinstance(d.get("bookingOptions"), dict) else "",
            })
    return found


def try_accept_cookies(page):
    for label in ("Accept", "Accept all", "Prijať", "Súhlasím", "I agree"):
        try:
            page.get_by_role("button", name=re.compile(label, re.I)).first.click(timeout=2000)
            return
        except Exception:
            continue


def scrape(url, depart, headed=False, timeout_ms=45000, dump_path=None):
    captured = []
    raw_dump = []

    def on_response(resp):
        if "graphql" not in resp.url.lower():
            return
        try:
            data = resp.json()
        except Exception:
            return
        if dump_path:
            raw_dump.append({"url": resp.url, "data": data})
        captured.extend(extract_from_json(data))

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not headed)
        ctx = browser.new_context(
            locale="en-GB",
            viewport={"width": 1366, "height": 900},
            user_agent=(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
            ),
        )
        page = ctx.new_page()
        page.on("response", on_response)
        page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
        try_accept_cookies(page)

        # počkaj na výsledky
        try:
            page.wait_for_selector("[data-test='ResultCardWrapper']", timeout=timeout_ms)
        except PWTimeout:
            print("Upozornenie: výsledky sa nenačítali (možno bot-ochrana / zmena stránky).",
                  file=sys.stderr)
        page.wait_for_timeout(3000)

        # fallback: ak GraphQL nič nedalo, vyparsuj ceny (a prvý čas HH:MM) z DOM
        if not captured:
            cards = page.locator("[data-test='ResultCardWrapper']")
            for i in range(min(cards.count(), 30)):
                text = cards.nth(i).inner_text()
                m = re.search(r"(\d[\d\s.,]*)\s*€|€\s*(\d[\d\s.,]*)", text)
                if m:
                    raw = (m.group(1) or m.group(2)).replace("\xa0", "").replace(" ", "")
                    raw = raw.replace(",", ".")
                    tm = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", text)
                    departure = f"{depart}T{tm.group(0).zfill(5)}:00.000Z" if tm else ""
                    try:
                        captured.append({"price": float(raw), "currency": "EUR", "id": "",
                                         "origin_code": "", "origin_city": "", "origin_country": "",
                                         "departure_time": departure, "booking_url": ""})
                    except ValueError:
                        pass
        browser.close()

    if dump_path:
        Path(dump_path).write_text(json.dumps(raw_dump, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"Surové GraphQL odpovede uložené do {Path(dump_path).resolve()}", file=sys.stderr)

    # jedna (najlacnejšia) ponuka na každý odlet z daného letiska; ponuky bez známeho času nezlučujeme
    best = {}
    for r in captured:
        if r["departure_time"]:
            key = (r["origin_code"] or r["origin_city"], r["departure_time"])
        else:
            key = (r["id"], r["price"])
        if key not in best or r["price"] < best[key]["price"]:
            best[key] = r
    return sorted(best.values(), key=lambda r: r["price"])


def save_csv(rows, origin, dest, depart):  # origin = zadaný slug(y), použije sa ak mesto nepoznáme
    # starý prices.csv bez stĺpca departure_time by sa pomiešal s novým formátom
    if CSV_PATH.exists():
        with CSV_PATH.open(encoding="utf-8") as f:
            first_line = f.readline().strip()
        if first_line != ",".join(CSV_HEADER):
            backup = CSV_PATH.with_name(f"prices_old_{datetime.now():%Y%m%d_%H%M%S}.csv")
            CSV_PATH.rename(backup)
            print(f"Starý formát CSV premenovaný na {backup.name}", file=sys.stderr)

    new = not CSV_PATH.exists()
    with CSV_PATH.open("a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if new:
            w.writerow(CSV_HEADER)
        now = datetime.now().isoformat(timespec="seconds")
        for r in rows:
            # ak čas odletu nepoznáme, uložíme aspoň deň odletu (polnoc)
            departure = r["departure_time"] or f"{depart}T00:00:00.000Z"
            w.writerow([now, r["origin_city"] or origin, r["origin_country"], dest, departure,
                        r["price"], r["currency"]])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("origin")
    ap.add_argument("dest")
    ap.add_argument("depart", help="YYYY-MM-DD")
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--json", action="store_true",
                    help="vypíše výsledok ako JSON na stdout (logy idú na stderr)")
    ap.add_argument("--no-csv", action="store_true", help="neukladať do prices.csv")
    ap.add_argument("--dump-graphql", metavar="SÚBOR",
                    help="uloží surové GraphQL odpovede do súboru (na ladenie extrakcie)")
    args = ap.parse_args()

    # v JSON režime musí byť stdout čistý
    log = sys.stderr if args.json else sys.stdout

    url = build_url(args.origin, args.dest, args.depart)
    print(f"Načítavam: {url}", file=log)
    rows = scrape(url, args.depart, headed=args.headed, dump_path=args.dump_graphql)

    if not rows:
        print("Nenašli sa žiadne ceny. Skús --headed a pozri, čo sa deje.", file=sys.stderr)
        sys.exit(1)

    if not any(r["departure_time"] for r in rows):
        print("Upozornenie: čas odletu sa nepodarilo zistiť. "
              "Spusti s --dump-graphql súbor.json a pozri, kde je čas v odpovedi.", file=sys.stderr)

    if args.json:
        result = {
            "scrapedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "offers": [
                {
                    "price": r["price"],
                    "currency": r["currency"],
                    "id": r["id"],
                    "departureTime": r["departure_time"],
                    "originCode": r["origin_code"],
                    "originCity": r["origin_city"],
                    "originCountry": r["origin_country"],
                    "bookingUrl": r["booking_url"],
                }
                for r in rows
            ],
        }
        sys.stdout.write(json.dumps(result, ensure_ascii=True))
        sys.stdout.flush()
    else:
        print(f"\nNájdených {len(rows)} ponúk. Top 10 najlacnejších:")
        for r in rows[:10]:
            print(f"  {r['price']:.2f} {r['currency']}  {r['departure_time'] or '(čas neznámy)'}"
                  f"  {r['origin_city']} {r['origin_country']}".rstrip())

    if not args.no_csv:
        save_csv(rows, args.origin, args.dest, args.depart)
        print(f"\nUložené do {CSV_PATH.resolve()}", file=log)


if __name__ == "__main__":
    main()