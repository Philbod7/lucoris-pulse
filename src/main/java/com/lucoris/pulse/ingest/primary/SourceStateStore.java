package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.core.domain.PrimarySourceState;
import java.time.Instant;
import java.util.Map;

/**
 * Port: Betriebszustand je Primärquelle (eine Zeile pro Manifest-Quelle). Erfolg setzt den
 * Fehlerzustand zurück und schreibt die Lauf-Zähler; ein Fehler erhöht {@code consecutiveFailures}
 * und hält die Meldung fest. {@code lastAttemptAt} ist der restart-feste Fälligkeitsanker des
 * Pollers — das Intervall selbst bleibt im Manifest.
 */
public interface SourceStateStore {

    /** Alle Zustandszeilen, keyed nach {@code sourceId}. Quellen ohne Zeile: noch nie versucht. */
    Map<String, PrimarySourceState> loadAll();

    /** Erfolgreicher Lauf: Fehlerzustand zurücksetzen, Zähler festhalten. */
    void recordSuccess(String sourceId, Instant at, int fetched, int newItems, int deduped);

    /** Fehlgeschlagener Lauf: {@code consecutiveFailures} erhöhen, Fehlertext festhalten. */
    void recordFailure(String sourceId, Instant at, String error);
}
