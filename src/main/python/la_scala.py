#!/usr/bin/env python3
"""Public La Scala ticket listings. JSON on stdout, diagnostics on stderr.

Only events published on the tickets page are covered. Online availability is
not total capacity or tickets sold. Prices are published tariffs, including
zones with no online seats left. No ticket purchasing or seat reservation.
"""
import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import html
import hashlib
import random
import json
import re
import sys
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
try:
    from zoneinfo import ZoneInfo
except ImportError:  # Python < 3.9
    ZoneInfo = None

PAGE_URL = "https://www.teatroallascala.org/en/tickets.html"


def fetch(url):
    request = Request(url, headers={"User-Agent": "LaScalaCourseScraper/1.0", "Accept": "application/json,text/html"})
    with urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8-sig")


def _last_sunday(year, month):
    d = datetime(year, month, 31)  # marec aj október majú 31 dní
    return d - timedelta(days=(d.weekday() + 1) % 7)


def rome_aware(naive):
    """Lokálny čas divadla -> aware datetime. Funguje aj bez balíčka tzdata (Windows)."""
    if ZoneInfo is not None:
        try:
            return naive.replace(tzinfo=ZoneInfo("Europe/Rome"))
        except Exception:
            pass
    start = _last_sunday(naive.year, 3).replace(hour=2)
    end = _last_sunday(naive.year, 10).replace(hour=3)
    hours = 2 if start <= naive < end else 1
    return naive.replace(tzinfo=timezone(timedelta(hours=hours)))


def try_accept_cookies(page):
    for label in ("Accept all", "Accept", "Agree", "Accetta", "Prijať"):
        try:
            page.get_by_role("button", name=re.compile(label, re.I)).first.click(timeout=1500)
            return
        except Exception:
            continue


def check_feed_host(url):
    if urlparse(url).netloc != urlparse(PAGE_URL).netloc:
        raise ValueError("Unexpected calendar host")


def fetch_feed_browser(headed=False, timeout_ms=45000):
    """Ako Kiwi scraper: skutočný Chromium. Vráti (feed_url, text odpovede)."""
    from playwright.sync_api import sync_playwright  # import až tu, aby testy nepotrebovali Playwright
    captured = {}

    def on_response(resp):
        if "eventcalendarsalesprices" in resp.url.lower() and resp.status == 200 and "url" not in captured:
            try:
                captured["text"] = resp.text()
                captured["url"] = resp.url
            except Exception:
                pass

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=not headed)
        try:
            ctx = browser.new_context(
                locale="en-GB",
                viewport={"width": 1366, "height": 900},
                user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"),
            )
            page = ctx.new_page()
            page.on("response", on_response)
            page.goto(PAGE_URL, wait_until="domcontentloaded", timeout=timeout_ms)
            try_accept_cookies(page)
            for _ in range(int(timeout_ms / 500)):
                if captured:
                    break
                page.wait_for_timeout(500)
            if not captured:
                # stránka feed nezavolala sama: nájdi ho v HTML a stiahni cez kontext prehliadača (cookies)
                feed = discover_feed(page.content())
                r = ctx.request.get(feed, headers={"Referer": PAGE_URL, "Accept": "application/json"},
                                    timeout=timeout_ms)
                if not r.ok:
                    raise ValueError("Calendar feed HTTP %s" % r.status)
                captured["url"], captured["text"] = feed, r.text()
        finally:
            browser.close()
    check_feed_host(captured["url"])
    return captured["url"], captured["text"]


def fetch_feed_plain():
    source = discover_feed(fetch(PAGE_URL))
    return source, fetch(source)


def discover_feed(page):
    match = re.search(r"axios\.get\(['\"]([^'\"]*eventCalendarSalesPrices\.aspx[^'\"]*)", page)
    if not match:
        raise ValueError("The tickets page no longer exposes the expected calendar feed")
    url = urljoin(PAGE_URL, html.unescape(match.group(1)))
    if urlparse(url).netloc != urlparse(PAGE_URL).netloc:
        raise ValueError("Unexpected calendar host")
    return url


def number(value):
    if value is None or value == "":
        return None
    parsed = Decimal(str(value))
    if not parsed.is_finite() or parsed != parsed.to_integral_value() or parsed < 0:
        raise ValueError("Invalid non-negative integer: %r" % value)
    return int(parsed)


def money(value):
    cents = number(value)
    return None if cents is None else format(Decimal(cents) / 100, ".2f")


def clean_title(value):
    value = html.unescape(value or "")
    return " ".join(re.sub(r"<[^>]*>", " ", value).split())


def parse_prices(fragment):
    if not fragment:
        return []
    if "<!" in fragment:
        raise ValueError("Unexpected declaration in prices XML")
    root = ET.fromstring("<prices>" + fragment + "</prices>")
    result = []
    for zone in root.findall("zone"):
        for price in zone.findall("price"):
            result.append({
                "zoneId": zone.get("id"), "zoneName": zone.get("description"),
                "availableSeats": number(zone.get("avail")),
                "tariffId": price.get("id"), "tariffName": price.get("name"),
                "price": money(price.get("price")),
                "presaleFee": money(price.get("presale")),
                "commission": money(price.get("commission")),
            })
    return result


def parse_payload(payload, source_url, scraped_at=None):
    rows = payload.get("Table") if isinstance(payload, dict) else None
    if not isinstance(rows, list) or not rows:
        raise ValueError("Missing or empty Table: refusing to report a successful empty scrape")
    events = []
    seen = set()
    for row in rows:
        if row.get("_deleted") is True:
            continue
        event_id = number(row["evtId"])
        if event_id is None or event_id in seen:
            raise ValueError("Missing or duplicate event ID")
        seen.add(event_id)
        title = clean_title(row["cntTitle"])
        if not title:
            raise ValueError("Missing event title")
        start = rome_aware(datetime.strptime(row["evtDateOffset"], "%d/%m/%Y %H:%M"))
        events.append({
            "eventId": str(event_id), "title": title, "category": row.get("navDescr"),
            "startsAt": start.isoformat(), "timeZone": "Europe/Rome",
            "bookingUrl": row.get("linkUrl"),
            "minAvailablePrice": money(row.get("evpMinTicketPrices")),
            "currency": "EUR", "availableSeats": number(row.get("evpAvailability")),
            "soldTickets": None, "soldTicketsStatus": "NOT_PUBLISHED",
            "prices": parse_prices(row.get("evpTicketPrices")),
            "rawSource": json.dumps(row, ensure_ascii=False),
        })
    if not events:
        raise ValueError("No non-deleted events in calendar")
    return {"scrapedAt": scraped_at or datetime.now(timezone.utc).isoformat(),
            "sourceUrl": source_url, "events": events}


def simulate_sales(events, capacity=2000, seed=42):
    if capacity < 0 or capacity > 2147483647:
        raise ValueError("Simulation capacity must be between 0 and 2147483647")
    for event in events:
        digest = hashlib.sha256((str(seed) + ":" + event["eventId"]).encode()).digest()
        event["simulatedSoldTickets"] = random.Random(digest).randint(0, capacity)
        event["simulationCapacity"] = capacity
        event["simulationSeed"] = str(seed)
        event["simulationMethod"] = "SYNTHETIC_UNIFORM_V1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="JSON output (default)")
    parser.add_argument("--no-browser", action="store_true", help="bez Playwrightu (len urllib)")
    parser.add_argument("--headed", action="store_true", help="zobrazí okno prehliadača (ladenie)")
    parser.add_argument("--dump-feed", metavar="SÚBOR", help="uloží surovú odpoveď kalendára (ladenie)")
    parser.add_argument("--simulate-sales", action="store_true")
    parser.add_argument("--simulation-capacity", type=int, default=2000)
    parser.add_argument("--simulation-seed", type=int, default=42)
    args = parser.parse_args()
    try:
        source, text = fetch_feed_plain() if args.no_browser else fetch_feed_browser(headed=args.headed)
        if args.dump_feed:
            with open(args.dump_feed, "w", encoding="utf-8") as f:
                f.write(text)
        result = parse_payload(json.loads(text.lstrip("\ufeff")), source)
        if args.simulate_sales:
            simulate_sales(result["events"], args.simulation_capacity, args.simulation_seed)
        sys.stdout.write(json.dumps(result, ensure_ascii=True))
        sys.stdout.flush()
        print("La Scala: fetched %d events; tickets sold are not published." % len(result["events"]), file=sys.stderr)
    except Exception as exc:
        print("La Scala scrape failed: %s" % exc, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())