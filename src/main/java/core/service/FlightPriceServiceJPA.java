package core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import core.entity.FlightPrice;

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
    public List<FlightPrice> getFlightTicket(String origin, String destination, LocalDateTime departureTime) {
        return entityManager.createQuery("SELECT f FROM FlightPrice f WHERE f.origin = :origin AND f.destination = :destination AND f.departureTime = :departureTime", FlightPrice.class)
                .setParameter("origin", origin)
                .setParameter("destination", destination)
                .setParameter("departureTime", departureTime)
                .getResultList();
    }
}