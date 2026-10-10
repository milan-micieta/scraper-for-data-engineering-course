import sys
import unittest
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))
from ryanair import (
    build_availability_url,
    build_search_url,
    clean_codes,
    extract_offers,
    window_departures,
)


class RyanairTests(unittest.TestCase):
    def setUp(self):
        self.payload = {
            "currency": "EUR",
            "trips": [{
                "origin": "VIE",
                "originName": "Vienna",
                "destination": "STN",
                "destinationName": "London (Stansted)",
                "dates": [{
                    "dateOut": "2026-11-15T00:00:00.000",
                    "flights": [
                        {
                            "flightNumber": "FR 731",
                            "segments": [{"origin": "VIE", "destination": "STN"}],
                            "time": ["2026-11-15T09:45:00.000", "2026-11-15T11:05:00.000"],
                            "regularFare": {"fares": [{"type": "ADT", "amount": 125.99}]},
                        },
                        {
                            "flightNumber": "FR 7356",
                            "segments": [{"origin": "VIE", "destination": "STN"}],
                            "time": ["2026-11-15T11:45:00.000", "2026-11-15T13:05:00.000"],
                            "regularFare": {"fares": [{"type": "ADT", "amount": 99.5}]},
                        },
                        {
                            "flightNumber": "FR 9000",
                            "segments": [{"origin": "VIE", "destination": "MAN"},
                                         {"origin": "MAN", "destination": "STN"}],
                            "time": ["2026-11-15T12:00:00", "2026-11-15T16:00:00"],
                            "regularFare": {"fares": [{"type": "ADT", "amount": 1}]},
                        },
                    ],
                }],
            }],
        }

    def test_search_and_availability_urls_use_one_way_direct_route(self):
        search = build_search_url("VIE", "STN", "2026-11-15")
        self.assertIn("originIata=VIE", search)
        self.assertIn("destinationIata=STN", search)
        self.assertIn("isReturn=false", search)
        availability = build_availability_url("VIE", "STN", "2026-11-17")
        self.assertIn("IncludeConnectingFlights=false", availability)
        self.assertIn("FlexDaysBeforeOut=2", availability)
        self.assertIn("FlexDaysOut=2", availability)

    def test_codes_are_normalized_and_duplicates_removed(self):
        self.assertEqual(clean_codes(" vie,STN,VIE "), ["VIE", "STN"])
        for invalid in ("", "VIENNA", "V1E"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                clean_codes(invalid)

    def test_sixty_days_are_covered_by_twelve_five_day_windows(self):
        start = date(2026, 11, 15)
        windows = window_departures(start, 60)
        self.assertEqual(len(windows), 12)
        for index, departure in enumerate(windows):
            self.assertEqual(
                departure - timedelta(days=2),
                start + timedelta(days=index * 5),
            )
        self.assertEqual(
            windows[-1] + timedelta(days=2),
            start + timedelta(days=59),
        )
        self.assertEqual(windows[-1], date(2027, 1, 11))

    def test_returns_cheapest_direct_adult_fare_for_date(self):
        offers = extract_offers(
            self.payload, "VIE", "STN", date(2026, 11, 15), date(2026, 11, 15),
        )
        self.assertEqual(len(offers), 1)
        self.assertEqual(offers[0]["price"], "99.5")
        self.assertEqual(offers[0]["currency"], "EUR")
        self.assertEqual(offers[0]["departureTime"], "2026-11-15T11:45:00")
        self.assertEqual(offers[0]["originCity"], "Vienna")
        self.assertEqual(offers[0]["destinationCity"], "London (Stansted)")

    def test_discards_dates_outside_requested_range(self):
        offers = extract_offers(
            self.payload, "VIE", "STN", date(2026, 11, 16), date(2026, 11, 20),
        )
        self.assertEqual(offers, [])

    def test_invalid_response_shape_is_not_treated_as_empty_success(self):
        for payload in ({}, {"trips": None}, {"trips": [{"origin": "VIE"}]}):
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                extract_offers(
                    payload, "VIE", "STN", date(2026, 11, 15), date(2026, 11, 15),
                )

    def test_bad_fare_is_reported(self):
        self.payload["trips"][0]["dates"][0]["flights"][0]["regularFare"]["fares"][0]["amount"] = "NaN"
        with self.assertRaises(ValueError):
            extract_offers(
                self.payload, "VIE", "STN", date(2026, 11, 15), date(2026, 11, 15),
            )


if __name__ == "__main__":
    unittest.main()
