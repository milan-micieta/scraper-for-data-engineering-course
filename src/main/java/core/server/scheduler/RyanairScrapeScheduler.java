package core.server.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.FlightPrice;
import core.service.FlightPriceService;
import core.service.FlightTicketSalesGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@ConditionalOnProperty(name = "ryanair.scrape.enabled", havingValue = "true", matchIfMissing = true)
public class RyanairScrapeScheduler {
    private static final Logger log = LoggerFactory.getLogger(RyanairScrapeScheduler.class);

    private final PythonService python;
    private final FlightPriceService flightPriceService;
    private final FlightTicketSalesGenerator salesGenerator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${python.exe:python}")
    private String pythonExe;
    @Value("${ryanair.python.script:src/main/python/ryanair.py}")
    private String script;
    @Value("${ryanair.python.timeout-seconds:600}")
    private long timeoutSeconds;
    @Value("${ryanair.scrape.origins:VIE,BUD,BTS,KSC}")
    private List<String> origins;
    @Value("${ryanair.scrape.destinations:STN}")
    private List<String> destinations;
    @Value("${ryanair.scrape.destination-label:london-united-kingdom}")
    private String destinationLabel;
    @Value("${ryanair.scrape.days:60}")
    private int days;
    @Value("${ryanair.scrape.start-offset-days:1}")
    private int startOffsetDays;
    @Value("${ryanair.scrape.start-date:}")
    private String startDate;
    @Value("${ryanair.scrape.delay-seconds:5.0}")
    private double delaySeconds;

    public RyanairScrapeScheduler(PythonService python, FlightPriceService flightPriceService,
                                  FlightTicketSalesGenerator salesGenerator) {
        this.python = python;
        this.flightPriceService = flightPriceService;
        this.salesGenerator = salesGenerator;
    }

    @Scheduled(cron = "${ryanair.scrape.cron:0 */15 * * * *}")
    public void scrapeAllRoutes() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> cleanOrigins = clean(origins);
            List<String> cleanDestinations = clean(destinations);
            if (cleanOrigins.isEmpty() || cleanDestinations.isEmpty()) {
                log.error("ryanair.scrape.origins and ryanair.scrape.destinations must not be empty");
                return;
            }

            List<String> command = new ArrayList<>(List.of(
                    pythonExe, script,
                    "--origins", String.join(",", cleanOrigins),
                    "--destinations", String.join(",", cleanDestinations),
                    "--days", String.valueOf(days),
                    "--start-offset-days", String.valueOf(startOffsetDays),
                    "--delay-seconds", String.valueOf(delaySeconds),
                    "--stream"
            ));
            if (startDate != null && !startDate.isBlank()) {
                command.addAll(List.of("--start-date", startDate.trim()));
            }

            log.info("Ryanair scrape started: {} -> {}, {} days, script {}",
                    cleanOrigins, cleanDestinations, days, script);
            AtomicInteger completedWindows = new AtomicInteger();
            AtomicInteger savedPrices = new AtomicInteger();
            python.runStreaming(command, timeoutSeconds, line -> {
                int savedInWindow = saveWindow(line);
                savedPrices.addAndGet(savedInWindow);
                completedWindows.incrementAndGet();
                log.info("Ryanair window {} completed: saved {} fare(s)",
                        completedWindows.get(), savedInWindow);
            });
            if (completedWindows.get() == 0) {
                throw new IllegalArgumentException("Ryanair returned no search windows");
            }
            log.info("Ryanair: saved {} cheapest daily fares for {} -> {} across {} windows",
                    savedPrices.get(), cleanOrigins, cleanDestinations, completedWindows.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ryanair scrape interrupted");
        } catch (Exception e) {
            log.error("Ryanair scrape failed; earlier completed windows may already be saved", e);
        } finally {
            running.set(false);
        }
    }

    private int saveWindow(String json) {
        ScrapeResult result;
        try {
            result = mapper.readValue(json, ScrapeResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Ryanair window response", e);
        }
        if (result.scrapedAt() == null || result.offers() == null) {
            throw new IllegalArgumentException("Incomplete Ryanair window response");
        }

        Instant scrapedAt = Instant.parse(result.scrapedAt());
        List<FlightPrice> prices = new ArrayList<>();
        for (Offer offer : result.offers()) {
            if (offer == null || offer.price() == null || offer.originCode() == null
                    || offer.destinationCode() == null || offer.departureTime() == null) {
                throw new IllegalArgumentException("Invalid Ryanair offer");
            }
            FlightPrice price = new FlightPrice();
            price.setSource("ryanair");
            price.setOrigin(offer.originCity() == null || offer.originCity().isBlank()
                    ? offer.originCode() : offer.originCity());
            price.setDestination(destinationLabel);
            price.setDepartureTime(parseDeparture(offer.departureTime()));
            price.setPrice(offer.price());
            price.setCurrency(offer.currency());
            price.setScrapedAt(scrapedAt);
            prices.add(price);
        }
        if (!prices.isEmpty()) {
            salesGenerator.generate(prices);
            flightPriceService.addFlightTicket(prices);
            for (FlightPrice price : prices) {
                log.info("{} -> {} {}: najlacnejšia {} {}", price.getOrigin(),
                        destinationLabel, price.getDepartureTime().toLocalDate(),
                        price.getPrice(), price.getCurrency());
            }
        }
        return prices.size();
    }

    private static List<String> clean(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    cleaned.add(value.trim().toUpperCase());
                }
            }
        }
        return cleaned;
    }

    private static LocalDateTime parseDeparture(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Invalid Ryanair departure time: " + value, e2);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScrapeResult(String scrapedAt, List<Offer> offers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Offer(BigDecimal price, String currency, String departureTime,
                 String originCode, String originCity, String destinationCode) {}
}
