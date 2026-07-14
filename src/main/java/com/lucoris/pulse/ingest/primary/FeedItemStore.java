package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.core.domain.PrimaryFeedItem;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Port: persistiert Feed-Meldungen. Die Dedup-Mechanik ist select-then-insert — erst
 * {@link #existingDedupKeys(Collection)} fragen, dann nur die fehlenden {@link #insert(List)}.
 * Kein {@code ON CONFLICT}: die Instanz ist single und der Poller sequenziell, ein Insert-Race
 * existiert nicht; der UNIQUE-Index auf {@code dedup_key} bleibt das harte Sicherheitsnetz
 * (schlägt er je an, scheitert nur der Batch dieser Quelle und der nächste Lauf heilt es).
 */
public interface FeedItemStore {

    /** Welche der Schlüssel sind schon gespeichert? Leere Eingabe -> leere Menge. */
    Set<String> existingDedupKeys(Collection<String> dedupKeys);

    /** Fügt die Zeilen in EINER Transaktion ein (StatelessSession + JDBC-Batch). */
    int insert(List<PrimaryFeedItem> rows);
}
