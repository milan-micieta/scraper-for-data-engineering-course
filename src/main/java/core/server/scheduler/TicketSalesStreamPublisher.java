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
 * Publikuje do Kafky jeden stream o La Scale: jedna správa na predstavenie a zber.
 * Správa obsahuje rozsah zverejnených cien AJ predaje (počet a cena predaných lístkov).
 * Predaje sú SYNTETICKÉ, La Scala ich nezverejňuje (pole sales.synthetic = true).
 *
 * Volaj až po uložení do DB. Chyba Kafky nikdy nezhodí zber dát, iba sa zaloguje.
 */
@Component
public class TicketSalesStreamPublisher {
    private static final Logger log = LoggerFactory.getLogger(TicketSalesStreamPublisher.class);

    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;

    @Value("${sales.stream.enabled:false}")
    private boolean enabled;
    @Value("${sales.stream.lascala-topic:lascala.prices}")
    private String topic;
    @Value("${lascala.simulation.seed:42}")
    private long simulationSeed;

    public TicketSalesStreamPublisher(ObjectMapper mapper, KafkaTemplate<String, String> kafka) {
        this.mapper = mapper;
        this.kafka = kafka;
    }

    public void offerTheatre(LaScala event) {
        if (!enabled) {
            return;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("schema_version", 1);
        message.put("event_id", event.getEventId());
        message.put("scraped_at", event.getScrapedAt());
        message.put("performance", event.getTitle());
        message.put("show_start", event.getStartsAt());
        message.put("currency", event.getCurrency());
        message.put("min_price", event.getMinPrice());
        message.put("max_price", event.getMaxPrice());
        Map<String, Object> salesBlock = salesBlock(event);
        if (salesBlock != null) {
            message.put("sales", salesBlock);
        }
        publish(event.getEventId(), message);
    }

    private Map<String, Object> salesBlock(LaScala event) {
        if (event.getSimulatedSoldTickets() == null) {
            return null;   // simulácia je vypnutá
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("synthetic", true);
        block.put("simulation_seed", String.valueOf(simulationSeed));
        block.put("sold_since_last_scrape", event.getSimulatedSoldTickets());
        block.put("sold_price", event.getSimulatedSoldPrice());
        return block;
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