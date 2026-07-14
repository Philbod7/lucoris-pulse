package com.lucoris.pulse.ingest.primary.adapter;

import com.lucoris.pulse.core.domain.PrimaryFeedItem;
import com.lucoris.pulse.ingest.primary.FeedItemStore;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Feed-Meldungs-Writer über Hibernate {@link StatelessSession} + JDBC-Batch — dasselbe Muster wie
 * der GDELT-{@code StatelessSessionFirehoseStore}: jeder Insert-Aufruf öffnet eine eigene Session,
 * committet direkt und läuft AUSSERHALB von Springs Transaktionsmanagement (Rollback in Tests
 * greift nicht -> Aufrufer muss TRUNCATE).
 */
@Component
@Profile("ingest")
public class StatelessSessionFeedItemStore implements FeedItemStore {

    private final SessionFactory sessionFactory;

    public StatelessSessionFeedItemStore(EntityManagerFactory entityManagerFactory) {
        this.sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    }

    @Override
    public Set<String> existingDedupKeys(Collection<String> dedupKeys) {
        if (dedupKeys == null || dedupKeys.isEmpty()) {
            return Set.of();
        }
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            List<String> found = session.createQuery(
                            "select i.dedupKey from PrimaryFeedItem i where i.dedupKey in :keys",
                            String.class)
                    .setParameter("keys", dedupKeys)
                    .getResultList();
            return new HashSet<>(found);
        }
    }

    @Override
    public int insert(List<PrimaryFeedItem> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            Transaction tx = session.beginTransaction();
            try {
                for (PrimaryFeedItem row : rows) {
                    session.insert(row);
                }
                tx.commit();
                return rows.size();
            } catch (RuntimeException e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            }
        }
    }
}
