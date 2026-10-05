package core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import core.entity.FlightPrice;
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
}