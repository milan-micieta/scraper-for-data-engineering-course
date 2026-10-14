package core.server.scheduler;

import core.entity.LaScala;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class LaScalaStorage {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void save(List<LaScala> events) {
        for (LaScala event : events) {
            entityManager.persist(event);
        }
        entityManager.flush();
    }

    @Transactional(readOnly = true)
    public Optional<Integer> findPreviousAvailableSeats(String eventId, Instant scrapedAt) {
        List<Integer> values = entityManager.createQuery("""
                select l.availableSeats
                from LaScala l
                where l.eventId = :eventId
                  and l.scrapedAt < :scrapedAt
                order by l.scrapedAt desc
                """, Integer.class)
                .setParameter("eventId", eventId)
                .setParameter("scrapedAt", scrapedAt)
                .setMaxResults(1)
                .getResultList();
        return values.stream().findFirst();
    }
}
