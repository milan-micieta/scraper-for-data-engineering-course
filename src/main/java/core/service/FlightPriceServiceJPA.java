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

    @Override
    public List<FlightPrice> getActiveLatestFlightTickets(String origin, String destination,
                                                          LocalDate departureDate, String source) {
        StringBuilder jpql = new StringBuilder("""
                SELECT f FROM FlightPrice f
                WHERE f.departureTime >= :now
                  AND NOT EXISTS (
                      SELECT newer.id
                      FROM FlightPrice newer
                      WHERE newer.origin = f.origin
                        AND newer.destination = f.destination
                        AND newer.departureTime = f.departureTime
                        AND newer.source = f.source
                        AND (
                            newer.scrapedAt > f.scrapedAt
                            OR (newer.scrapedAt = f.scrapedAt AND newer.id > f.id)
                        )
                  )
                """);
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
        jpql.append(" ORDER BY f.departureTime, f.origin, f.destination, f.source");

        TypedQuery<FlightPrice> query = entityManager.createQuery(jpql.toString(), FlightPrice.class);
        query.setParameter("now", LocalDateTime.now());
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