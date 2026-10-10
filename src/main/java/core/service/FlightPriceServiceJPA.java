package core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import core.entity.FlightPrice;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


@Transactional
public class FlightPriceServiceJPA implements FlightPriceService {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void addFlightTicket(List<FlightPrice> prices) {
        for (FlightPrice price : prices) {
            entityManager.persist(price);
        }
    }

    @Override
    public List<FlightPrice> getFlightTicket(String origin, String destination, LocalDate departureDate,
                                             String source) {
        StringBuilder jpql = new StringBuilder("SELECT f FROM FlightPrice f WHERE 1 = 1");
        if (origin != null && !origin.isBlank()) {
            jpql.append(" AND f.origin = :origin");
        }
        if (destination != null && !destination.isBlank()) {
            jpql.append(" AND f.destination = :destination");
        }
        if (source != null && !source.isBlank()) {
            jpql.append(" AND f.source = :source");
        }
        if (departureDate != null) {
            jpql.append(" AND f.departureTime >= :from AND f.departureTime < :to");
        }
        jpql.append(" ORDER BY f.departureTime, f.scrapedAt DESC");

        TypedQuery<FlightPrice> query = entityManager.createQuery(jpql.toString(), FlightPrice.class);
        if (origin != null && !origin.isBlank()) {
            query.setParameter("origin", origin);
        }
        if (destination != null && !destination.isBlank()) {
            query.setParameter("destination", destination);
        }
        if (source != null && !source.isBlank()) {
            query.setParameter("source", source);
        }
        if (departureDate != null) {
            query.setParameter("from", departureDate.atStartOfDay());
            query.setParameter("to", departureDate.plusDays(1).atStartOfDay());
        }
        query.setMaxResults(500);
        return query.getResultList();
    }
}