package core.service;

import core.entity.FlightPrice;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Generates clearly labelled synthetic sales snapshots for flights.
 */
public class FlightTicketSalesGenerator {
    private final boolean enabled;
    private final int capacity;
    private final long seed;
    private final long defaultIntervalSeconds;
    private final ConcurrentMap<String, Instant> lastScrapedAt = new ConcurrentHashMap<>();

    public FlightTicketSalesGenerator(boolean enabled, int capacity, long seed,
                                      long defaultIntervalSeconds) {
        if (capacity < 1) {
            throw new IllegalArgumentException("flight.sales-simulation.capacity must be positive");
        }
        if (defaultIntervalSeconds < 1) {
            throw new IllegalArgumentException(
                    "flight.sales-simulation.default-interval-seconds must be positive");
        }
        this.enabled = enabled;
        this.capacity = capacity;
        this.seed = seed;
        this.defaultIntervalSeconds = defaultIntervalSeconds;
    }

    public void generate(List<FlightPrice> prices) {
        if (!enabled || prices == null) {
            return;
        }
        for (FlightPrice price : prices) {
            if (price.getDepartureTime() == null || price.getOrigin() == null
                    || price.getDestination() == null) {
                throw new IllegalArgumentException("Cannot generate sales for an incomplete flight");
            }
            Instant scrapedAt = price.getScrapedAt() == null ? Instant.now() : price.getScrapedAt();
            String flightKey = price.getSource() + "|" + price.getOrigin() + "|"
                    + price.getDestination() + "|"
                    + price.getDepartureTime();
            Instant previousScrape = lastScrapedAt.put(flightKey, scrapedAt);
            long intervalSeconds = previousScrape == null
                    ? defaultIntervalSeconds
                    : Math.max(0, scrapedAt.getEpochSecond() - previousScrape.getEpochSecond());
            int sold = ticketsSoldDuring(intervalSeconds, scrapedAt, price.getDepartureTime(), flightKey);

            price.setSimulatedSoldTickets(sold);
            price.setSimulationCapacity(capacity);
            price.setSimulationSeed(String.valueOf(seed));
            price.setSimulationMethod("incremental-per-scrape-deterministic");
        }
    }

    private int ticketsSoldDuring(long intervalSeconds, Instant scrapedAt,
                                  LocalDateTime departure, String flightKey) {
        if (intervalSeconds == 0) {
            return 0;
        }
        double demand = demandAt(scrapedAt, departure);
        double expectedTickets = intervalSeconds / 3600.0 * (0.25 + demand * 1.25);
        long timeBucket = scrapedAt.getEpochSecond() / defaultIntervalSeconds;
        long randomSeed = seed ^ (long) flightKey.hashCode() ^ timeBucket;
        double randomPart = new SplittableRandom(randomSeed).nextDouble();
        return Math.max(0, (int) Math.floor(expectedTickets + randomPart));
    }

    private static double demandAt(Instant scrapedAt, LocalDateTime departure) {
        long hoursUntilDeparture = Duration.between(
                scrapedAt, departure.toInstant(ZoneOffset.UTC)).toHours();
        if (hoursUntilDeparture <= 0) {
            return 1.0;
        }
        return Math.max(0.1, Math.min(1.0,
                1.0 - (hoursUntilDeparture / (24.0 * 60.0)) * 0.8));
    }
}
