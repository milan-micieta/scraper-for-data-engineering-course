package core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Embeddable
@Getter
@Setter
public class LaScalaPrice {
    private String zoneId;
    @Column(length = 1000)
    private String zoneName;
    private Integer availableSeats;
    private String tariffId;
    @Column(length = 1000)
    private String tariffName;
    @Column(precision = 12, scale = 2)
    private BigDecimal price;
    @Column(precision = 12, scale = 2)
    private BigDecimal presaleFee;
    @Column(precision = 12, scale = 2)
    private BigDecimal commission;
}
