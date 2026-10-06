import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main" / "python"))
from la_scala import discover_feed, parse_payload, parse_prices, simulate_sales


class LaScalaTests(unittest.TestCase):
    def setUp(self):
        self.row = {
            "evtId": 123, "cntTitle": "Opera &amp; Ballet<br>Evening",
            "evtDateOffset": "06/12/2026 14:00", "evtDateOffset2": "2026-12-06T14:00:00+00:00",
            "evpAvailability": 0, "evpMinTicketPrices": None,
            "evpTicketPrices": '<zone id="1" description="Stalls" avail="0">'
            '<price id="a" name="Full" price="12345" presale="100" commission="0"/>'
            '<price id="b" name="Reduced" price="8000"/></zone>',
        }

    def parse(self, row=None):
        return parse_payload({"Table": [self.row if row is None else row]}, "https://example.test", "2026-10-05T12:00:00Z")["events"][0]

    def test_prices_include_sold_out_zones_and_all_tariffs(self):
        event = self.parse()
        self.assertEqual(event["title"], "Opera & Ballet Evening")
        self.assertEqual([p["price"] for p in event["prices"]], ["123.45", "80.00"])
        self.assertEqual(event["prices"][0]["presaleFee"], "1.00")
        self.assertIsNone(event["prices"][1]["commission"])
        self.assertEqual(event["availableSeats"], 0)
        self.assertIsNone(event["soldTickets"])
        self.assertIsNone(event["minAvailablePrice"])

    def test_theatre_local_time_handles_winter_and_summer(self):
        self.assertEqual(self.parse()["startsAt"], "2026-12-06T14:00:00+01:00")
        self.row["evtDateOffset"] = "05/10/2026 20:00"
        self.assertEqual(self.parse()["startsAt"], "2026-10-05T20:00:00+02:00")

    def test_missing_availability_is_not_zero(self):
        del self.row["evpAvailability"]
        self.assertIsNone(self.parse()["availableSeats"])

    def test_invalid_or_empty_feed_fails(self):
        for payload in ({}, {"Table": []}, {"Table": [self.row, self.row]}):
            with self.assertRaises(ValueError):
                parse_payload(payload, "source")
        with self.assertRaises(Exception):
            parse_prices('<zone broken')
        self.row["evpAvailability"] = -1
        with self.assertRaises(ValueError):
            self.parse()

    def test_each_performance_is_retained(self):
        second = copy.deepcopy(self.row)
        second["evtId"] = 124
        result = parse_payload({"Table": [self.row, second]}, "source")
        self.assertEqual(len(result["events"]), 2)

    def test_simulation_is_reproducible_bounded_and_separate(self):
        events = [self.parse()]
        simulate_sales(events, 2000, 42)
        value = events[0]["simulatedSoldTickets"]
        self.assertTrue(0 <= value <= 2000)
        self.assertIsNone(events[0]["soldTickets"])
        self.assertEqual(events[0]["soldTicketsStatus"], "NOT_PUBLISHED")
        self.assertEqual(events[0]["simulationMethod"], "SYNTHETIC_UNIFORM_V1")
        simulate_sales(events, 2000, 42)
        self.assertEqual(events[0]["simulatedSoldTickets"], value)
        simulate_sales(events, 0, 42)
        self.assertEqual(events[0]["simulatedSoldTickets"], 0)
        with self.assertRaises(ValueError):
            simulate_sales(events, -1)
        self.assertNotIn("simulatedSoldTickets", self.parse())

    def test_feed_url_discovered_from_page(self):
        self.assertEqual(discover_feed("axios.get('/web/cache/eventCalendarSalesPrices.aspx?seasonNavId=42&idLang=en-US', {})"),
                         "https://www.teatroallascala.org/web/cache/eventCalendarSalesPrices.aspx?seasonNavId=42&idLang=en-US")
        with self.assertRaises(ValueError):
            discover_feed("<h1>Unexpected website</h1>")


if __name__ == "__main__":
    unittest.main()
