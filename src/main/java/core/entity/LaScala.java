package core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

/** One performance snapshot per scrape. All La Scala data is kept in this table. */
@Entity
@Table(name = "lascala", indexes = {
        @Index(name = "idx_lascala_event_scraped", columnList = "event_id,scraped_at"),
        @Index(name = "idx_lascala_starts", columnList = "starts_at")
}, uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "scraped_at"}))
@Getter
@Setter
public class LaScala {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
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
    private BigDecimal minPrice;
    @Column(precision = 12, scale = 2)
    private BigDecimal maxPrice;
    private String currency;
    private Integer availableSeats;

    // The source does not publish real sales; these fields are intentionally nullable.
    private Integer soldTickets;
    private String soldTicketsStatus;

    // Synthetic sales generated for the course, measured since the previous scrape.
    private Integer simulatedSoldTickets;
    @Column(precision = 12, scale = 2)
    private BigDecimal simulatedSoldPrice;
    // Retained as simulation metadata; it is not used to calculate sold tickets.
    private Integer simulationCapacity;
    private String simulationSeed;
    private String simulationMethod;

    @Column(nullable = false)
    private Instant scrapedAt;
    @Column(nullable = false, length = 2000)
    private String sourceUrl;
    @Column(nullable = false, columnDefinition = "text")
    private String rawSource;
}
