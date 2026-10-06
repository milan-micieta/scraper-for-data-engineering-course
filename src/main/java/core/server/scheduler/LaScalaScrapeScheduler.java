package core.server.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import core.entity.LaScalaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "lascala.scrape.enabled", havingValue = "true", matchIfMissing = true)
public class LaScalaScrapeScheduler {
    private static final Logger log = LoggerFactory.getLogger(LaScalaScrapeScheduler.class);
    private final PythonService python;
    private final LaScalaStorage storage;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean();

    @Value("${lascala.python.exe:${kiwi.python.exe:python3}}")
    private String pythonExe;
    @Value("${lascala.python.script:src/main/python/la_scala.py}")
    private String script;
    @Value("${lascala.python.timeout-seconds:90}")
    private long timeout;
    @Value("${lascala.scrape.on-startup:true}")
    private boolean onStartup;
    @Value("${lascala.simulation.enabled:false}")
    private boolean simulateSales;
    @Value("${lascala.simulation.capacity:2000}")
    private int simulationCapacity;
    @Value("${lascala.simulation.seed:42}")
    private long simulationSeed;

    public LaScalaScrapeScheduler(PythonService python, LaScalaStorage storage, ObjectMapper mapper) {
        this.python = python;
        this.storage = storage;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startup() {
        if (onStartup) scrape();
    }

    @Scheduled(cron = "${lascala.scrape.cron:0 5,20,35,50 * * * *}")
    public void scrape() {
        if (!running.compareAndSet(false, true)) return;
        try {
            List<String> command = new ArrayList<>(List.of(pythonExe, script, "--json"));
            if (simulateSales) {
                command.addAll(List.of("--simulate-sales", "--simulation-capacity", String.valueOf(simulationCapacity),
                        "--simulation-seed", String.valueOf(simulationSeed)));
            }
            String json = python.run(command, timeout);
            Batch batch = mapper.readValue(json, Batch.class);
            if (batch.scrapedAt() == null || batch.sourceUrl() == null
                    || batch.events() == null || batch.events().isEmpty()) {
                throw new IllegalArgumentException("Incomplete La Scala response");
            }
            for (LaScalaEvent event : batch.events()) {
                if (event.getEventId() == null || event.getTitle() == null || event.getStartsAt() == null
                        || event.getSoldTickets() != null || !"NOT_PUBLISHED".equals(event.getSoldTicketsStatus())) {
                    throw new IllegalArgumentException("Invalid La Scala event");
                }
                event.setScrapedAt(batch.scrapedAt());
                event.setSourceUrl(batch.sourceUrl());
            }
            storage.save(batch.events());
            log.info("La Scala: saved {} performance snapshots with zone prices; tickets sold are not published", batch.events().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("La Scala scrape interrupted");
        } catch (Exception e) {
            log.error("La Scala scrape failed; no snapshot saved", e);
        } finally {
            running.set(false);
        }
    }

    record Batch(Instant scrapedAt, String sourceUrl, List<LaScalaEvent> events) {}
}
