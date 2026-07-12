package com.lucoris.pulse.ingest.gdelt.adapter;

import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import com.lucoris.pulse.core.domain.IngestLog;
import com.lucoris.pulse.ingest.gdelt.FirehoseStore;
import com.lucoris.pulse.ingest.gdelt.MissingEventRef;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Firehose-Writer über Hibernate {@link StatelessSession} + JDBC-Batch (Batchgröße global via
 * {@code hibernate.jdbc.batch_size}). Umgeht den Persistence-Context; jeder Insert-Aufruf öffnet
 * eine eigene StatelessSession, committet direkt und läuft damit AUSSERHALB von Springs
 * Transaktionsmanagement (Rollback in Tests greift nicht -> Aufrufer muss TRUNCATE).
 *
 * <p>Bei {@link GdeltMention} bleibt {@code mention_id} unbelegt und wird beim Insert aus
 * {@code mention_seq} (pooled-lo, allocationSize 50) vergeben.
 */
@Component
@Profile("ingest")
public class StatelessSessionFirehoseStore implements FirehoseStore {

    private final SessionFactory sessionFactory;

    public StatelessSessionFirehoseStore(EntityManagerFactory entityManagerFactory) {
        this.sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    }

    @Override
    public int insertEvents(List<GdeltEvent> rows) {
        return insertAll(rows);
    }

    @Override
    public int insertMentions(List<GdeltMention> rows) {
        return insertAll(rows);
    }

    @Override
    public int insertGkg(List<GdeltGkg> rows) {
        return insertAll(rows);
    }

    @Override
    public int insertAtomic(List<?> rows) {
        return insertAll(rows);
    }

    @Override
    public boolean isFileProcessed(String filename) {
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            return session.get(IngestLog.class, filename) != null;
        }
    }

    @Override
    public List<MissingEventRef> findMissingEventRefs(Instant sliceStart, Instant sliceEndExcl) {
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            return session.createQuery(
                            "select distinct new com.lucoris.pulse.ingest.gdelt.MissingEventRef("
                                    + "m.globalEventId, m.eventTimeDate) "
                                    + "from GdeltMention m "
                                    + "where m.mentionTimeDate >= :start and m.mentionTimeDate < :end "
                                    + "and m.eventTimeDate is not null "
                                    + "and not exists (select 1 from GdeltEvent e "
                                    + "where e.globalEventId = m.globalEventId)",
                            MissingEventRef.class)
                    .setParameter("start", sliceStart)
                    .setParameter("end", sliceEndExcl)
                    .getResultList();
        }
    }

    private int insertAll(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            Transaction tx = session.beginTransaction();
            try {
                for (Object row : rows) {
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
