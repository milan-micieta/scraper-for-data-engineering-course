#!/usr/bin/env python3
"""Collect direct Ryanair fares from its public flight-search website."""
import argparse
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import json
import math
import sys
import time as time_module
from urllib.parse import urlencode

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import TimeoutError as PlaywrightTimeoutError
from playwright.sync_api import sync_playwright

SITE_URL = "https://www.ryanair.com/gb/en"
SEARCH_URL = SITE_URL + "/trip/flights/select"
AVAILABILITY_URL = "https://www.ryanair.com/api/booking/v4/en-gb/availability"
AVAILABILITY_PATH = "/api/booking/v4/en-gb/availability"
FLEX_DAYS = 2
WINDOW_DAYS = FLEX_DAYS * 2 + 1


def clean_codes(values):
    codes = [value.strip().upper() for value in values.split(",") if value.strip()]
    if not codes:
        raise ValueError("At least one IATA airport code is required")
    if any(len(code) != 3 or not code.isalpha() for code in codes):
        raise ValueError("Airport codes must be three-letter IATA codes")
    return list(dict.fromkeys(codes))


def build_search_url(origin, destination, depart, currency="EUR"):
    params = {
        "adults": 1,
        "teens": 0,
        "children": 0,
        "infants": 0,
        "dateOut": depart,
        "dateIn": "",
        "isReturn": "false",
        "discount": 0,
        "promoCode": "",
        "originIata": origin,
        "destinationIata": destination,
        "isConnectedFlight": "false",
        "isFamilyDiscount": "false",
        "isFlexibleFare": "false",
        "currency": currency,
    }
    return SEARCH_URL + "?" + urlencode(params)


def build_availability_url(origin, destination, depart):
    params = {
        "ADT": 1,
        "TEEN": 0,
        "CHD": 0,
        "INF": 0,
        "Origin": origin,
        "Destination": destination,
        "promoCode": "",
        "IncludeConnectingFlights": "false",
        "DateOut": depart,
        "DateIn": "",
        "FlexDaysBeforeOut": FLEX_DAYS,
        "FlexDaysOut": FLEX_DAYS,
        "FlexDaysBeforeIn": FLEX_DAYS,
        "FlexDaysIn": FLEX_DAYS,
        "RoundTrip": "false",
        "IncludePrimeFares": "false",
        "ToUs": "AGREED",
    }
    return AVAILABILITY_URL + "?" + urlencode(params)


def window_departures(start_date, days):
    return [
        start_date + timedelta(days=offset + FLEX_DAYS)
        for offset in range(0, days, WINDOW_DAYS)
    ]


def adult_fare(flight):
    regular_fare = flight.get("regularFare")
    fares = regular_fare.get("fares") if isinstance(regular_fare, dict) else None
    if not isinstance(fares, list):
        return None

    prices = []
    for fare in fares:
        if not isinstance(fare, dict) or fare.get("type") != "ADT":
            continue
        try:
            amount = Decimal(str(fare["amount"]))
        except (KeyError, InvalidOperation):
            raise ValueError("Ryanair returned an invalid adult fare")
        if not amount.is_finite() or amount < 0:
            raise ValueError("Ryanair adult fare must be finite and non-negative")
        prices.append(amount)
    return min(prices) if prices else None


def extract_offers(payload, origin, destination, start_date, end_date):
    if not isinstance(payload, dict) or not isinstance(payload.get("trips"), list):
        raise ValueError("Ryanair availability response has no trips list")

    best_by_date = {}
    for trip in payload["trips"]:
        if not isinstance(trip, dict):
            raise ValueError("Ryanair returned an invalid trip")
        if trip.get("origin") not in (None, origin):
            continue
        if trip.get("destination") not in (None, destination):
            continue
        dates = trip.get("dates")
        if not isinstance(dates, list):
            raise ValueError("Ryanair trip has no dates list")

        for day in dates:
            if not isinstance(day, dict):
                raise ValueError("Ryanair returned an invalid date")
            try:
                flight_date = date.fromisoformat(str(day["dateOut"])[:10])
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError("Ryanair returned an invalid departure date") from exc
            if not start_date <= flight_date <= end_date:
                continue

            flights = day.get("flights")
            if not isinstance(flights, list):
                raise ValueError("Ryanair date has no flights list")
            for flight in flights:
                if not isinstance(flight, dict):
                    raise ValueError("Ryanair returned an invalid flight")
                segments = flight.get("segments")
                if not isinstance(segments, list):
                    raise ValueError("Ryanair flight has no segments list")
                if len(segments) != 1:
                    continue
                times = flight.get("time")
                if not isinstance(times, list) or not times:
                    raise ValueError("Ryanair direct flight has no departure time")
                try:
                    departure = datetime.fromisoformat(times[0])
                except (TypeError, ValueError) as exc:
                    raise ValueError("Ryanair returned an invalid departure time") from exc

                price = adult_fare(flight)
                if price is None:
                    continue
                offer = {
                    "originCode": origin,
                    "originCity": trip.get("originName") or origin,
                    "destinationCode": destination,
                    "destinationCity": trip.get("destinationName") or destination,
                    "departureTime": departure.isoformat(timespec="seconds"),
                    "price": str(price),
                    "currency": payload.get("currency") or "EUR",
                    "id": flight.get("flightNumber") or "",
                }
                current = best_by_date.get(flight_date)
                if current is None or price < current[0]:
                    best_by_date[flight_date] = (price, offer)

    return [
        offer
        for _, offer in sorted(best_by_date.values(), key=lambda item: item[1]["departureTime"])
    ]


def _checked_json(response):
    if not response.ok:
        raise RuntimeError("Ryanair availability returned HTTP %d: %s" % (
            response.status, response.text()[:250],
        ))
    try:
        payload = response.json()
    except (ValueError, PlaywrightError) as exc:
        raise ValueError("Ryanair availability did not return valid JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError("Ryanair availability returned a non-object response")
    return payload


def scrape(origins, destinations, start_date, days=60, delay_seconds=1.0, on_batch=None):
    if not 1 <= days <= 365:
        raise ValueError("Days must be between 1 and 365")
    if not math.isfinite(delay_seconds) or delay_seconds < 0:
        raise ValueError("Delay must be a finite non-negative number")
    end_date = start_date + timedelta(days=days - 1)
    windows = window_departures(start_date, days)
    offers = []
    offer_count = 0
    failed_windows = 0

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        try:
            context = browser.new_context(locale="en-GB")
            page = context.new_page()
            captured_headers = {}

            def capture_availability_headers(request):
                if AVAILABILITY_PATH in request.url and not captured_headers:
                    captured_headers.update(request.headers)

            page.on("request", capture_availability_headers)
            first_origin = origins[0]
            first_destination = destinations[0]
            first_departure = windows[0]
            first_window_end = min(
                end_date,
                first_departure + timedelta(days=FLEX_DAYS),
            )
            print(
                "Ryanair %s -> %s %s..%s: searching" % (
                    first_origin, first_destination, start_date, first_window_end,
                ),
                file=sys.stderr,
                flush=True,
            )
            try:
                with page.expect_response(
                    lambda response: AVAILABILITY_PATH in response.url,
                    timeout=60_000,
                ) as first_response:
                    page.goto(
                        build_search_url(first_origin, first_destination, first_departure.isoformat()),
                        wait_until="domcontentloaded",
                        timeout=60_000,
                    )
                first_payload = _checked_json(first_response.value)
            except PlaywrightTimeoutError as exc:
                raise RuntimeError("Ryanair website did not return flight availability") from exc

            client_version = captured_headers.get("client-version")
            if not client_version:
                raise ValueError("Could not determine Ryanair website client version")
            api_headers = {
                "Accept": "application/json, text/plain, */*",
                "client": captured_headers.get("client", "desktop"),
                "client-version": client_version,
                "Referer": page.url,
            }

            request_count = 0
            for origin in origins:
                for destination in destinations:
                    for window_index, query_date in enumerate(windows):
                        window_start = max(
                            start_date,
                            query_date - timedelta(days=FLEX_DAYS),
                        )
                        window_end = min(
                            end_date,
                            query_date + timedelta(days=FLEX_DAYS),
                        )
                        is_initial_request = (
                            origin == first_origin
                            and destination == first_destination
                            and window_index == 0
                        )
                        if is_initial_request:
                            payload = first_payload
                        else:
                            if request_count:
                                time_module.sleep(delay_seconds)
                            print(
                                "Ryanair %s -> %s %s..%s: searching" % (
                                    origin, destination, window_start, window_end,
                                ),
                                file=sys.stderr,
                                flush=True,
                            )
                            try:
                                response = context.request.get(
                                    build_availability_url(origin, destination, query_date.isoformat()),
                                    headers=api_headers,
                                    timeout=60_000,
                                )
                                payload = _checked_json(response)
                            except (PlaywrightError, RuntimeError, ValueError) as exc:
                                failed_windows += 1
                                print(
                                    "Ryanair %s -> %s %s..%s failed: %s" % (
                                        origin, destination, window_start, window_end, exc,
                                    ),
                                    file=sys.stderr,
                                    flush=True,
                                )
                                continue
                        request_count += 1
                        try:
                            window_offers = extract_offers(
                                payload, origin, destination, start_date, end_date,
                            )
                        except ValueError as exc:
                            failed_windows += 1
                            print(
                                "Ryanair %s -> %s %s..%s response invalid: %s" % (
                                    origin, destination, window_start, window_end, exc,
                                ),
                                file=sys.stderr,
                                flush=True,
                            )
                            continue
                        offer_count += len(window_offers)
                        if on_batch is None:
                            offers.extend(window_offers)
                        if not window_offers:
                            print(
                                "Ryanair %s -> %s %s..%s: no direct fares" % (
                                    origin, destination, window_start, window_end,
                                ),
                                file=sys.stderr,
                                flush=True,
                            )
                        if on_batch is not None:
                            on_batch({
                                "scrapedAt": datetime.now(timezone.utc).isoformat(),
                                "offers": window_offers,
                            })
            context.close()
        finally:
            browser.close()

    return {
        "scrapedAt": datetime.now(timezone.utc).isoformat(),
        "offers": sorted(offers, key=lambda offer: (
            offer["originCode"], offer["departureTime"], Decimal(offer["price"]),
        )),
        "offerCount": offer_count,
        "failedWindows": failed_windows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origins", required=True, help="Comma-separated IATA origin codes")
    parser.add_argument("--destinations", required=True, help="Comma-separated IATA destination codes")
    parser.add_argument("--days", type=int, default=60)
    parser.add_argument("--start-offset-days", type=int, default=1)
    parser.add_argument("--start-date", help="Optional fixed start date (YYYY-MM-DD)")
    parser.add_argument("--delay-seconds", type=float, default=1.0)
    parser.add_argument("--stream", action="store_true", help="Write one JSON line per completed search window")
    args = parser.parse_args()

    def emit_batch(batch):
        print(json.dumps(batch, ensure_ascii=False), flush=True)

    try:
        origins = clean_codes(args.origins)
        destinations = clean_codes(args.destinations)
        if args.start_date:
            start_date = date.fromisoformat(args.start_date)
        else:
            start_date = date.today() + timedelta(days=args.start_offset_days)
        result = scrape(
            origins, destinations, start_date, args.days,
            args.delay_seconds,
            emit_batch if args.stream else None,
        )
    except (OSError, PlaywrightError, RuntimeError, ValueError) as exc:
        print("Ryanair scrape failed: %s" % exc, file=sys.stderr)
        return 1

    if not args.stream:
        print(json.dumps(result, ensure_ascii=False))
    print(
        "Ryanair: fetched %d cheapest daily offers; %d windows failed." % (
            result["offerCount"], result["failedWindows"],
        ),
        file=sys.stderr,
    )
    return 1 if result["failedWindows"] else 0


if __name__ == "__main__":
    sys.exit(main())
