package core.server.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.LaScala;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publikuje do Kafky jeden stream o La Scale (sales.stream.lascala-snapshot-topic):
 * jedna správa na predstavenie a zber so VŠETKÝMI poľami entity LaScala vrátane raw_source.
 * Zodpovedá jednému riadku tabuľky lascala. Prirodzený kľúč pre deduplikáciu je (event_id, scraped_at).
 *
 * Polia simulated_* sú SYNTETICKÉ predaje (počet a cena predaných lístkov od predchádzajúceho scrapu),
 * La Scala skutočné predaje nezverejňuje. Sú null, ak je simulácia vypnutá.
 *
 * Volaj až po uložení do DB. Chyba Kafky nikdy nezhodí zber dát, iba sa zaloguje.
 */
@Component
public class TicketSalesStreamPublisher {
    private static final Logger log = LoggerFactory.getLogger(TicketSalesStreamPublisher.class);

    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;

    @Value("${sales.stream.snapshot.enabled:false}")
    private boolean enabled;
    @Value("${sales.stream.lascala-snapshot-topic:lascala.snapshots}")
    private String topic;

    public TicketSalesStreamPublisher(ObjectMapper mapper, KafkaTemplate<String, String> kafka) {
        this.mapper = mapper;
        this.kafka = kafka;
    }

    /** Všetky dáta z entity LaScala (okrem interného DB id) ako jedna správa. */
    public void offerSnapshot(LaScala event) {
        if (!enabled) {
            return;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("schema_version", 1);
        message.put("event_id", event.getEventId());
        message.put("scraped_at", event.getScrapedAt());
        message.put("source_url", event.getSourceUrl());
        message.put("title", event.getTitle());
        message.put("category", event.getCategory());
        message.put("starts_at", event.getStartsAt());
        message.put("time_zone", event.getTimeZone());
        message.put("booking_url", event.getBookingUrl());
        message.put("currency", event.getCurrency());
        message.put("min_price", event.getMinPrice());
        message.put("max_price", event.getMaxPrice());
        message.put("available_seats", event.getAvailableSeats());
        message.put("sold_tickets", event.getSoldTickets());
        message.put("sold_tickets_status", event.getSoldTicketsStatus());
        message.put("simulated_sold_tickets", event.getSimulatedSoldTickets());
        message.put("simulated_sold_price", event.getSimulatedSoldPrice());
        message.put("simulation_capacity", event.getSimulationCapacity());
        message.put("simulation_seed", event.getSimulationSeed());
        message.put("simulation_method", event.getSimulationMethod());
        message.put("raw_source", rawJson(event.getRawSource()));
        publish(event.getEventId(), message);
    }

    /** raw_source je JSON v texte; pošleme ho ako objekt, a ak sa nedá rozparsovať, ako reťazec. */
    private Object rawJson(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
    }

    private void publish(String key, Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            kafka.send(topic, key, json).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Kafka: publikovanie do {} zlyhalo (key={})", topic, key, error);
                } else {
                    log.debug("Kafka: {} partition={} offset={} key={}", topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset(), key);
                }
            });
        } catch (Exception e) {
            // serializácia alebo blokujúci send (Kafka nedostupná): zber dát sa kvôli tomu nezastaví
            log.error("Kafka: publikovanie do {} zlyhalo (key={})", topic, key, e);
        }
    }
}