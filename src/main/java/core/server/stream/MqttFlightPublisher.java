package core.server.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.FlightPrice;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publikuje letenkové dáta do MQTT brokera CloudAMQP.
 * Topic: di/{team}/flights (QoS 1).
 *
 * Formát zodpovedá špecifikácii SKUPINA2:
 * {"schema_version":1, "event_id":"flight-...", "bundle_id":..., "sold_at":"...",
 *  "ticket_id":"uuid", "source":"kiwi|ryanair", "from":"VIE", "to":"MIL",
 *  "departure_local":"...", "arrival":null, "airline":"", "flight_number":"",
 *  "stops":0, "price_eur":..., "currency":"EUR"}
 *
 * Chyba MQTT nikdy nezhodí zber dát – iba sa zaloguje.
 */
@Component
public class MqttFlightPublisher {
    private static final Logger log = LoggerFactory.getLogger(MqttFlightPublisher.class);
    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final ObjectMapper mapper;
    private MqttClient client;

    @Value("${mqtt.enabled:false}")
    private boolean enabled;
    @Value("${mqtt.host:swallow.lmq.cloudamqp.com}")
    private String host;
    @Value("${mqtt.port:8883}")
    private int port;
    @Value("${mqtt.username:}")
    private String username;
    @Value("${mqtt.password:}")
    private String password;
    @Value("${mqtt.team:team9}")
    private String team;

    private String topic;

    public MqttFlightPublisher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("MQTT flight publisher disabled");
            return;
        }
        this.topic = "di/" + team + "/flights";
        try {
            String clientId = team + "-producer-" + UUID.randomUUID().toString().substring(0, 8);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(false);
            options.setConnectionTimeout(15);
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(true);
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            client = new MqttClient("ssl://" + host + ":" + port, clientId, new MemoryPersistence());
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    log.warn("MQTT connection lost: {}", cause != null ? cause.getMessage() : "unknown");
                }

                @Override
                public void messageArrived(String t, MqttMessage m) {
                    // We only publish; ignore received messages.
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken d) {
                    // QoS 1 ack received
                }
            });
            client.connect(options);
            log.info("MQTT connected to {}:{} topic={}", host, port, topic);
        } catch (MqttException e) {
            log.error("MQTT connect failed - flight streaming disabled: {}", e.getMessage());
            client = null;
        }
    }

    /** Publishes one flight price snapshot to the MQTT topic. */
    public void publishFlight(FlightPrice price) {
        if (!enabled || client == null || !client.isConnected()) {
            return;
        }
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("schema_version", 1);
            msg.put("event_id", "flight-" + price.getId());
            msg.put("bundle_id", bundleId(price));
            msg.put("sold_at", price.getScrapedAt() != null ? price.getScrapedAt().toString() : "");
            msg.put("ticket_id", UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            msg.put("source", price.getSource() != null ? price.getSource() : "kiwi");
            msg.put("from", toIata(price.getOrigin()));
            msg.put("to", toIata(price.getDestination()));
            msg.put("departure_local", price.getDepartureTime() != null
                    ? price.getDepartureTime().format(LOCAL_FMT) : "");
            msg.put("arrival", null);
            msg.put("airline", "");
            msg.put("flight_number", "");
            msg.put("stops", 0);
            msg.put("price_eur", price.getPrice() != null ? price.getPrice().doubleValue() : 0);
            msg.put("currency", price.getCurrency() != null ? price.getCurrency() : "EUR");

            String json = mapper.writeValueAsString(msg);
            MqttMessage mqttMsg = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
            mqttMsg.setQos(1);
            mqttMsg.setRetained(false);
            client.publish(topic, mqttMsg);
            log.debug("MQTT: published flight {} ({})", price.getId(),
                    price.getOrigin() + "->" + price.getDestination());
        } catch (Exception e) {
            log.error("MQTT: publish flight {} failed: {}", price.getId(), e.getMessage());
        }
    }

    /** Deterministic bundle ID based on route + date. */
    private long bundleId(FlightPrice price) {
        String key = (price.getSource() != null ? price.getSource() : "kiwi") + "|"
                + (price.getOrigin() != null ? price.getOrigin() : "") + "|"
                + (price.getDestination() != null ? price.getDestination() : "") + "|"
                + (price.getDepartureTime() != null ? price.getDepartureTime().toString() : "");
        return Math.abs((long) key.hashCode()) & 0x7FFFFFFF;
    }

    /** Maps origin/destination label to IATA-like 3-letter code. */
    private String toIata(String label) {
        if (label == null || label.isBlank()) return "";
        if (label.length() == 3 && label.equals(label.toUpperCase())) {
            return label;
        }
        String lower = label.toLowerCase();
        if (lower.contains("vienna") || lower.contains("wien")) return "VIE";
        if (lower.contains("budapest") || lower.contains("hungary")) return "BUD";
        if (lower.contains("bratislava") || lower.contains("slovakia")) return "BTS";
        if (lower.contains("kosice")) return "KSC";
        if (lower.contains("milan") || lower.contains("italy")) return "MIL";
        if (lower.contains("munich") || lower.contains("munchen")) return "MUC";
        if (lower.contains("london")) return "LON";
        String letters = label.replaceAll("[^a-zA-Z]", "");
        return letters.substring(0, Math.min(3, letters.length())).toUpperCase();
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException e) {
                log.warn("MQTT close: {}", e.getMessage());
            }
        }
    }
}
