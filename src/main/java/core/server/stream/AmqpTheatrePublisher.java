package core.server.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.LaScala;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publikuje La Scala snapshoty do RabbitMQ brokera CloudAMQP.
 * Exchange: di.{team}.theatre, routing key: theatre.sales.
 *
 * Formát zodpovedá špecifikácii SKUPINA2:
 * {"schema_version":1, "event_id":"theatre-...", "bundle_id":..., "sold_at":"...",
 *  "perf_id":..., "title":"...", "venue":"Teatro alla Scala", "type":"Opera",
 *  "starts_at":"...", "category":2, "seat_no":..., "price_eur":..., "currency":"EUR"}
 *
 * Chyba RabbitMQ nikdy nezhodí zber dát – iba sa zaloguje.
 */
@Component
public class AmqpTheatrePublisher {
    private static final Logger log = LoggerFactory.getLogger(AmqpTheatrePublisher.class);

    private final ObjectMapper mapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${amqp.theatre.enabled:false}")
    private boolean enabled;
    @Value("${amqp.team:team9}")
    private String team;

    private String exchange;
    private static final String ROUTING_KEY = "theatre.sales";

    public AmqpTheatrePublisher(ObjectMapper mapper, RabbitTemplate rabbitTemplate) {
        this.mapper = mapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    void initializeExchange() {
        exchange = "di." + team + ".theatre";
    }

    /** Publishes one La Scala event snapshot to the RabbitMQ exchange. */
    public void publishTheatre(LaScala event) {
        if (!enabled) {
            return;
        }
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("schema_version", 1);
            msg.put("event_id", "theatre-" + event.getEventId());
            msg.put("bundle_id", bundleId(event));
            msg.put("sold_at", event.getScrapedAt() != null ? event.getScrapedAt().toString() : "");
            msg.put("perf_id", parsePerfId(event.getEventId()));
            msg.put("title", event.getTitle());
            msg.put("venue", "Teatro alla Scala");
            msg.put("type", event.getCategory() != null ? event.getCategory() : "Opera");
            msg.put("starts_at", event.getStartsAt() != null ? event.getStartsAt().toString() : "");
            msg.put("category", priceZone(event.getMinPrice()));
            msg.put("seat_no", null);
            msg.put("price_eur", event.getMinPrice() != null ? event.getMinPrice().doubleValue() : 0);
            msg.put("currency", event.getCurrency() != null ? event.getCurrency() : "EUR");

            String json = mapper.writeValueAsString(msg);
            rabbitTemplate.convertAndSend(exchange, ROUTING_KEY, json);
            log.debug("AMQP: published theatre {} ({})", event.getEventId(), event.getTitle());
        } catch (Exception e) {
            log.error("AMQP: publish theatre {} failed: {}", event.getEventId(), e.getMessage());
        }
    }

    /** Deterministic bundle ID based on event + scrape time. */
    private long bundleId(LaScala event) {
        String key = event.getEventId() + "|"
                + (event.getScrapedAt() != null ? event.getScrapedAt().toString() : "");
        return Math.abs((long) key.hashCode()) & 0x7FFFFFFF;
    }

    /** Parses event_id to integer perf_id (SKUPINA2 uses integer perf_id). */
    private Integer parsePerfId(String eventId) {
        try {
            return Integer.parseInt(eventId);
        } catch (NumberFormatException e) {
            return Math.abs((int) (long) eventId.hashCode()) & 0x7FFFFFFF;
        }
    }

    /**
     * Approximates price zone (1 = most expensive) from min_price.
     * La Scala zones: 1=most expensive (highest price), 4=cheapest.
     * We don't have explicit zone data, so we estimate from price range.
     */
    private int priceZone(java.math.BigDecimal minPrice) {
        if (minPrice == null) return 4;
        double price = minPrice.doubleValue();
        // La Scala typical price ranges (EUR):
        // Zone 1: 80-250+, Zone 2: 45-120, Zone 3: 25-80, Zone 4: 15-45
        if (price >= 80) return 1;
        if (price >= 45) return 2;
        if (price >= 25) return 3;
        return 4;
    }
}
