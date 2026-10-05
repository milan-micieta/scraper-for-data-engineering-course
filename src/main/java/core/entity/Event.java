package core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "theatre_events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Názov hry / predstavenia (napr. "Hamlet")
    @Column(nullable = false)
    private String title;

    @Column(name = "theatre_name", nullable = false, length = 150)
    private String theatreName;

    @Column(length = 100)
    private String city;

    @Column(name = "date_time", nullable = false)
    private LocalDateTime dateTime;

    @Column(name = "price_from", precision = 10, scale = 2)
    private BigDecimal priceFrom;

    @Column(name = "price_to", precision = 10, scale = 2)
    private BigDecimal priceTo;

    @Column(length = 10)
    private String currency = "EUR";

    // Stav lístkov (napr. "AVAILABLE", "SOLD_OUT", "CANCELLED")
    @Column(length = 30)
    private String status;

    @Column(nullable = false, length = 50)
    private String source;

    @Column(name = "scraped_at", nullable = false)
    private Instant scrapedAt;
}