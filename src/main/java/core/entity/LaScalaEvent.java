package core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** One observation per performance per scrape; old prices are retained. */
@Entity
@Table(name = "la_scala_events", indexes = {
    @Index(name = "idx_scala_event_scraped", columnList = "event_id,scraped_at"),
    @Index(name = "idx_scala_starts", columnList = "starts_at")
}, uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "scraped_at"}))
@Getter
@Setter
public class LaScalaEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String eventId;
    @Column(nullable = false, length = 1000)
    private String title;
    private String category;
    @Column(nullable = false)
    private OffsetDateTime startsAt;
    private String timeZone;
    @Column(length = 2000)
    private String bookingUrl;
    @Column(precision = 12, scale = 2)
    private BigDecimal minAvailablePrice;
    private String currency;
    private Integer availableSeats;
    // NULL means unknown, never infer sales from online availability.
    private Integer soldTickets;
    private String soldTicketsStatus;
    // Synthetic test data; never an estimate of real ticket sales.
    private Integer simulatedSoldTickets;
    private Integer simulationCapacity;
    private String simulationSeed;
    private String simulationMethod;
    @Column(nullable = false)
    private Instant scrapedAt;
    @Column(nullable = false, length = 2000)
    private String sourceUrl;
    @Column(nullable = false, columnDefinition = "text")
    private String rawSource;
    @ElementCollection
    @CollectionTable(name = "la_scala_prices", joinColumns = @JoinColumn(name = "snapshot_id"))
    private List<LaScalaPrice> prices = new ArrayList<>();
}
