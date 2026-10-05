package core.server.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.FlightPrice;
import core.service.FlightPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;

/**
 * Každých 15 minút (cron, konfigurovateľné) prejde všetky kombinácie odletové mesto x cieľ
 * a pre každý z nasledujúcich N dní (jednosmerná cesta, iba priame lety) uloží do tabuľky
 * flight_prices iba najlacnejšiu letenku daného dňa.
 *
 * Konfigurácia (slugy skopíruj z URL na kiwi.com):
 *   kiwi.scrape.origins=kosice-slovakia,vienna-austria,budapest-hungary
 *   kiwi.scrape.destinations=london-united-kingdom
 *   kiwi.scrape.start-date=2026-11-15      (prázdne = zajtra)
 *   kiwi.scrape.days=7
 */
@Component
public class KiwiScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(KiwiScrapeScheduler.class);

    private PythonService pythonService;
    private FlightPriceService flightPriceService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @Value("${kiwi.scrape.origins:}")
    private List<String> origins;

    @Value("${kiwi.scrape.destinations:}")
    private List<String> destinations;

    @Value("${kiwi.scrape.start-date:}")
    private String startDate;

    /** Pre koľko po sebe idúcich dní zbierať ceny. */
    @Value("${kiwi.scrape.days:7}")
    private int days;

    /** Pauza medzi dvoma spusteniami scrapera, aby Kiwi neblokovalo príliš rýchle požiadavky. */
    @Value("${kiwi.scrape.delay-seconds:5}")
    private long delaySeconds;

    public KiwiScrapeScheduler(PythonService pythonService, FlightPriceService flightPriceService) {
        this.pythonService = pythonService;
        this.flightPriceService = flightPriceService;
    }

    @Scheduled(cron = "${kiwi.scrape.cron:0 */15 * * * *}")
    public void scrapeAllRoutes() {
        if (origins == null || origins.isEmpty() || destinations == null || destinations.isEmpty()) {
            log.warn("kiwi.scrape.origins / kiwi.scrape.destinations nie sú nastavené, nič nescrapujem");
            return;
        }
        LocalDate first = (startDate == null || startDate.isBlank())
                ? LocalDate.now().plusDays(1)
                : LocalDate.parse(startDate.trim());

        try {
            for (String origin : origins) {
                for (String dest : destinations) {
                    if (origin.isBlank() || dest.isBlank() || origin.trim().equals(dest.trim())) {
                        continue;
                    }
                    scrapeRoute(origin.trim(), dest.trim(), first);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scrapeRoute(String origin, String dest, LocalDate first) throws InterruptedException {
        for (int i = 0; i < days; i++) {
            LocalDate date = first.plusDays(i);
            if (date.isBefore(LocalDate.now())) {
                continue;
            }
            try {
                scrapeDay(origin, dest, date);
            } catch (Exception e) {
                // chyba jedného dňa nesmie zastaviť ostatné ani budúce behy
                log.error("Scraping {} -> {} na {} zlyhal", origin, dest, date, e);
            }
            Thread.sleep(delaySeconds * 1000);
        }
    }

    private void scrapeDay(String origin, String dest, LocalDate date) throws Exception {
        String json = pythonService.runKiwiScraper(origin, dest, date.toString());
        ScrapeResult result = objectMapper.readValue(json, ScrapeResult.class);

        if (result.offers() == null) {
            log.warn("{} -> {} {}: scraper nevrátil žiadne ponuky", origin, dest, date);
            return;
        }
        Offer cheapest = result.offers().stream()
                .filter(o -> o.price() != null)
                .min(Comparator.comparing(Offer::price))
                .orElse(null);
        if (cheapest == null) {
            log.warn("{} -> {} {}: scraper nevrátil žiadne ponuky", origin, dest, date);
            return;
        }

        FlightPrice fp = new FlightPrice();
        fp.setScrapedAt(result.scrapedAt() != null ? Instant.parse(result.scrapedAt()) : Instant.now());
        fp.setOrigin(origin);
        fp.setDestination(dest);
        fp.setDepartureTime(parseDeparture(cheapest.departureTime(), date.atStartOfDay()));
        fp.setPrice(cheapest.price());
        fp.setCurrency(cheapest.currency());

        flightPriceService.addFlightTicket(List.of(fp));
        log.info("{} -> {} {}: najlacnejšia {} {}", origin, dest, date, fp.getPrice(), fp.getCurrency());
    }

    private static LocalDateTime parseDeparture(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            // lokálny čas letiska bez zóny, napr. 2026-11-15T22:05:00
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                // varianta s príponou Z / offsetom, hodiny necháme tak, ako sú
                return OffsetDateTime.parse(value).toLocalDateTime();
            } catch (DateTimeParseException e2) {
                return fallback;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScrapeResult(String scrapedAt, List<Offer> offers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Offer(BigDecimal price, String currency, String id, String bookingUrl, String departureTime) {
    }
}