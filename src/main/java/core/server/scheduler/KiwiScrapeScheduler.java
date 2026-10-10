package core.server.scheduler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.FlightPrice;
import core.service.FlightPriceService;
import core.service.FlightTicketSalesGenerator;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Každých N minút (cron) prejde všetky ciele a pre každý z nasledujúcich dní (jednosmerná cesta,
 * iba priame lety) spraví JEDNO vyhľadávanie zo všetkých odletových miest naraz.
 * Z výsledkov uloží do flight_prices najlacnejšiu letenku za každú krajinu odletu.
 *
 * Konfigurácia (slugy skopíruj z URL na kiwi.com):
 *   kiwi.scrape.origins=kosice-slovakia,bratislava-slovakia,vienna-austria,budapest-hungary
 *   kiwi.scrape.destinations=london-united-kingdom
 *   kiwi.scrape.start-offset-days=1        (0 = od dnes, 1 = od zajtra; okno sa každý deň posúva)
 *   kiwi.scrape.start-date=2026-11-15      (voliteľné; ak je zadané, okno sa neposúva)
 *   kiwi.scrape.days=7
 */
@Component
public class KiwiScrapeScheduler {

    private static final Logger log = LoggerFactory.getLogger(KiwiScrapeScheduler.class);

    private PythonService pythonService;
    private FlightPriceService flightPriceService;
    private ObjectMapper objectMapper = new ObjectMapper();
    private final FlightTicketSalesGenerator salesGenerator;

    @Value("${kiwi.scrape.origins:}")
    private List<String> origins;

    @Value("${kiwi.scrape.destinations:}")
    private List<String> destinations;

    /** Pevný začiatok okna. Prázdne = okno sa posúva podľa start-offset-days. */
    @Value("${kiwi.scrape.start-date:}")
    private String startDate;

    /** Od koľkého dňa od dnešku sa zbiera (0 = dnes, 1 = zajtra). */
    @Value("${kiwi.scrape.start-offset-days:1}")
    private int startOffsetDays;

    /** Pre koľko po sebe idúcich dní zbierať ceny. */
    @Value("${kiwi.scrape.days:7}")
    private int days;

    /** Pauza medzi dvoma spusteniami scrapera, aby Kiwi neblokovalo príliš rýchle požiadavky. */
    @Value("${kiwi.scrape.delay-seconds:5}")
    private long delaySeconds;

    public KiwiScrapeScheduler(PythonService pythonService, FlightPriceService flightPriceService,
                               FlightTicketSalesGenerator salesGenerator) {
        this.pythonService = pythonService;
        this.flightPriceService = flightPriceService;
        this.salesGenerator = salesGenerator;
    }

    @Scheduled(cron = "${kiwi.scrape.cron:0 */15 * * * *}")
    public void scrapeAllRoutes() {
        List<String> cleanOrigins = clean(origins);
        List<String> cleanDestinations = clean(destinations);
        if (cleanOrigins.isEmpty() || cleanDestinations.isEmpty()) {
            log.warn("kiwi.scrape.origins / kiwi.scrape.destinations nie sú nastavené, nič nescrapujem");
            return;
        }
        // všetky odletové mestá v jednej URL: a,b,c
        String originsSlug = String.join(",", cleanOrigins);

        LocalDate first = (startDate == null || startDate.isBlank())
                ? LocalDate.now().plusDays(startOffsetDays)
                : LocalDate.parse(startDate.trim());

        try {
            for (String dest : cleanDestinations) {
                for (int i = 0; i < days; i++) {
                    LocalDate date = first.plusDays(i);
                    if (date.isBefore(LocalDate.now())) {
                        continue;
                    }
                    try {
                        scrapeDay(originsSlug, dest, date);
                    } catch (Exception e) {
                        // chyba jedného dňa nesmie zastaviť ostatné ani budúce behy
                        log.error("Scraping {} -> {} na {} zlyhal", originsSlug, dest, date, e);
                    }
                    Thread.sleep(delaySeconds * 1000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scrapeDay(String originsSlug, String dest, LocalDate date) throws Exception {
        String json = pythonService.runKiwiScraper(originsSlug, dest, date.toString());
        ScrapeResult result = objectMapper.readValue(json, ScrapeResult.class);

        if (result.offers() == null || result.offers().isEmpty()) {
            log.warn("{} -> {} {}: scraper nevrátil žiadne ponuky", originsSlug, dest, date);
            return;
        }

        // najlacnejšia ponuka za každú krajinu odletu
        // (ak scraper krajinu nezistil, všetky ponuky padnú do jednej skupiny "")
        Map<String, Offer> cheapestPerCountry = new LinkedHashMap<>();
        for (Offer o : result.offers()) {
            if (o.price() == null) {
                continue;
            }
            String key = o.originCountry() == null ? "" : o.originCountry();
            cheapestPerCountry.merge(key, o,
                    (a, b) -> a.price().compareTo(b.price()) <= 0 ? a : b);
        }
        if (cheapestPerCountry.size() == 1 && cheapestPerCountry.containsKey("")) {
            log.warn("{} -> {} {}: scraper nezistil krajinu odletu, ukladám len jednu najlacnejšiu ponuku",
                    originsSlug, dest, date);
        }

        Instant scrapedAt = result.scrapedAt() != null ? Instant.parse(result.scrapedAt()) : Instant.now();
        List<FlightPrice> entities = new ArrayList<>();
        for (Offer o : cheapestPerCountry.values()) {
            FlightPrice fp = new FlightPrice();
            fp.setScrapedAt(scrapedAt);
            fp.setOrigin(originLabel(o, originsSlug));
            fp.setDestination(dest);
            fp.setDepartureTime(parseDeparture(o.departureTime(), date.atStartOfDay()));
            fp.setPrice(o.price());
            fp.setCurrency(o.currency());
            entities.add(fp);
        }

        salesGenerator.generate(entities);
        flightPriceService.addFlightTicket(entities);
        for (FlightPrice fp : entities) {
            log.info("{} -> {} {}: najlacnejšia {} {}", fp.getOrigin(), dest,
                    date, fp.getPrice(), fp.getCurrency());
        }
    }

    private static List<String> clean(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
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

    private static String originLabel(Offer o, String fallback) {
        String city = o.originCity() == null ? "" : o.originCity().trim();
        String country = o.originCountry() == null ? "" : o.originCountry().trim();
        if (city.isBlank() && country.isBlank()) {
            return fallback;
        }
        if (city.isBlank()) {
            return country;
        }
        if (country.isBlank()) {
            return city;
        }
        return city + ", " + country;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScrapeResult(String scrapedAt, List<Offer> offers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Offer(BigDecimal price, String currency, String id, String bookingUrl, String departureTime,
                 String originCode, String originCity, String originCountry) {
    }
}