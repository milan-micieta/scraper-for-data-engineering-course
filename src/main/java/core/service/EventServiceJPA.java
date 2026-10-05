package core.service;

import core.entity.Event;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

import java.util.List;

@Transactional
public class EventServiceJPA implements EventService {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void addEvent(List<Event> tickets) {
        for (Event event : tickets) {
            entityManager.persist(event);
        }
    }

    @Override
    public List<Event> getEvents() {
        // TODO: Implement this method
        return List.of();
    }
}
