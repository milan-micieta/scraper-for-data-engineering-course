package core.server.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.LaScala;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class LaScalaWebhookNotifier {
    private static final Logger log = LoggerFactory.getLogger(LaScalaWebhookNotifier.class);

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String bearerToken;
    private final boolean enabled;
    private final Duration timeout;

    public LaScalaWebhookNotifier(
            ObjectMapper mapper,
            @Value("${lascala.webhook.enabled:false}") boolean enabled,
            @Value("${lascala.webhook.url:}") String url,
            @Value("${lascala.webhook.bearer-token:}") String bearerToken,
            @Value("${lascala.webhook.timeout-seconds:10}") long timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("lascala.webhook.timeout-seconds must be positive");
        }
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        this.endpoint = url == null || url.isBlank() ? null : URI.create(url.trim());
        this.bearerToken = bearerToken == null ? "" : bearerToken.trim();
        this.enabled = enabled;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public void notify(Instant scrapedAt, String sourceUrl, List<LaScala> events) {
        if (!enabled || endpoint == null) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "source", "la-scala",
                "scrapedAt", scrapedAt,
                "sourceUrl", sourceUrl,
                "events", events
        );
        final String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize La Scala webhook payload", e);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Webhook-Event", "lascala.scrape.completed")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!bearerToken.isBlank()) {
            request.header("Authorization", "Bearer " + bearerToken);
        }

        try {
            HttpResponse<String> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(response.body().length(), 500)));
            }
            log.info("La Scala webhook delivered: {} event(s), HTTP {}",
                    events.size(), response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("La Scala webhook interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("La Scala webhook delivery failed", e);
        }
    }
}
