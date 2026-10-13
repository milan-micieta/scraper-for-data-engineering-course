package core.server.scheduler;

import core.entity.LaScala;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Calculates the synthetic sales increment from the change in online
 * availability between consecutive scrapes.
 */
@Component
public class LaScalaSalesSimulator {
    private static final String METHOD = "incremental-per-scrape-deterministic";

    @Value("${lascala.simulation.enabled:false}")
    private boolean enabled;
    @Value("${lascala.simulation.capacity:2000}")
    private int capacity;
    @Value("${lascala.simulation.seed:42}")
    private long seed;

    private final ConcurrentMap<String, Observation> last = new ConcurrentHashMap<>();

    private record Observation(Integer availableSeats) {}

    public void simulate(LaScala event) {
        if (!enabled) {
            return;
        }
        if (event.getEventId() == null || event.getStartsAt() == null || event.getScrapedAt() == null) {
            throw new IllegalArgumentException("Cannot generate La Scala sales for an incomplete event");
        }

        Observation previous = last.put(event.getEventId(), new Observation(event.getAvailableSeats()));
        int sold = soldFromAvailability(previous, event.getAvailableSeats());

        event.setSimulatedSoldTickets(sold);
        event.setSimulatedSoldPrice(soldPrice(event, event.getScrapedAt(), sold));
        event.setSimulationCapacity(capacity);
        event.setSimulationSeed(String.valueOf(seed));
        event.setSimulationMethod(METHOD);
    }

    private static int soldFromAvailability(Observation previous, Integer currentAvailableSeats) {
        if (previous == null || previous.availableSeats() == null || currentAvailableSeats == null) {
            return 0;
        }
        return Math.max(0, previous.availableSeats() - currentAvailableSeats);
    }

    private BigDecimal soldPrice(LaScala event, Instant scrapedAt, int sold) {
        if (sold == 0 || event.getMinPrice() == null || event.getMaxPrice() == null) {
            return null;
        }
        double range = Math.max(0, event.getMaxPrice().doubleValue() - event.getMinPrice().doubleValue());
        long randomSeed = seed ^ (long) event.getEventId().hashCode() ^ scrapedAt.getEpochSecond();
        double value = event.getMinPrice().doubleValue()
                + new SplittableRandom(randomSeed)
                .nextDouble() * range;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
