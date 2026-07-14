package com.lucoris.pulse.ingest.primary.adapter;

import com.lucoris.pulse.core.domain.PrimarySourceState;
import com.lucoris.pulse.ingest.primary.SourceStateStore;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.Transaction;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Zustands-Writer je Quelle: get -> insert/update in EINER Transaktion. Kein {@code ON CONFLICT}
 * nötig — die Instanz ist single und der Poller sequenziell, zwei Schreiber auf dieselbe Zeile
 * gibt es nicht. Läuft wie der Firehose außerhalb von Springs Transaktionsmanagement.
 */
@Component
@Profile("ingest")
public class StatelessSessionSourceStateStore implements SourceStateStore {

    private final SessionFactory sessionFactory;

    public StatelessSessionSourceStateStore(EntityManagerFactory entityManagerFactory) {
        this.sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    }

    @Override
    public Map<String, PrimarySourceState> loadAll() {
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            return session.createQuery("from PrimarySourceState", PrimarySourceState.class)
                    .getResultList().stream()
                    .collect(Collectors.toMap(PrimarySourceState::getSourceId, s -> s));
        }
    }

    @Override
    public void recordSuccess(String sourceId, Instant at, int fetched, int newItems, int deduped) {
        upsert(sourceId, state -> {
            state.setLastAttemptAt(at);
            state.setLastSuccessAt(at);
            state.setConsecutiveFailures(0);
            state.setLastError(null);
            state.setLastFetched(fetched);
            state.setLastNew(newItems);
            state.setLastDeduped(deduped);
            // lastErrorAt bleibt stehen: "wann ging es zuletzt schief" ist auch nach Erholung wertvoll.
        });
    }

    @Override
    public void recordFailure(String sourceId, Instant at, String error) {
        upsert(sourceId, state -> {
            state.setLastAttemptAt(at);
            state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
            state.setLastError(error);
            state.setLastErrorAt(at);
        });
    }

    private void upsert(String sourceId, Consumer<PrimarySourceState> mutation) {
        try (StatelessSession session = sessionFactory.openStatelessSession()) {
            Transaction tx = session.beginTransaction();
            try {
                PrimarySourceState state = session.get(PrimarySourceState.class, sourceId);
                boolean neu = state == null;
                if (neu) {
                    state = new PrimarySourceState();
                    state.setSourceId(sourceId);
                }
                mutation.accept(state);
                if (neu) {
                    session.insert(state);
                } else {
                    session.update(state);
                }
                tx.commit();
            } catch (RuntimeException e) {
                if (tx.isActive()) {
                    tx.rollback();
                }
                throw e;
            }
        }
    }
}
