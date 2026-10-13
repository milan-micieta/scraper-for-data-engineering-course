package core.server.scheduler;

import core.entity.LaScala;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

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
}
