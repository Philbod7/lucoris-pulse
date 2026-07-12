package com.lucoris.pulse.ingest.gdelt;

import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import java.time.Instant;
import java.util.List;

/**
 * Port (Infrastruktur): Massen-Insert der Roh-Entities über Hibernate {@code StatelessSession}
 * + JDBC-Batch. Jeder Aufruf committet eigenständig (außerhalb eines Spring-Transaktionskontexts).
 */
public interface FirehoseStore {

    /** @return Anzahl eingefügter Zeilen. */
    int insertEvents(List<GdeltEvent> rows);

    /** @return Anzahl eingefügter Zeilen. */
    int insertMentions(List<GdeltMention> rows);

    /** @return Anzahl eingefügter Zeilen. */
    int insertGkg(List<GdeltGkg> rows);

    /**
     * Fügt beliebige Roh-Entities inklusive {@code IngestLog}-Einträge in EINER Transaktion ein
     * (atomar). So landet der {@code ingest_log}-Vermerk innerhalb derselben schreibenden
     * Transaktion wie die Nutzdaten. Für Batching die Zeilen nach Entity-Typ geordnet übergeben.
     *
     * @return Anzahl eingefügter Zeilen (inkl. Log-Einträge)
     */
    int insertAtomic(List<?> rows);

    /** @return {@code true}, wenn die Datei bereits in {@code ingest_log} vermerkt ist. */
    boolean isFileProcessed(String filename);

    /**
     * Ermittelt Events, die von committeten Mentions des angegebenen Slice-Fensters referenziert
     * werden, aber (noch) nicht in {@code gdelt_events} existieren.
     *
     * @param sliceStart    Fensterbeginn (inklusive) über {@code mention_time_date}
     * @param sliceEndExcl  Fensterende (exklusive)
     * @return distinkte (globalEventId, eventTimeDate) der fehlenden Events
     */
    List<MissingEventRef> findMissingEventRefs(Instant sliceStart, Instant sliceEndExcl);
}
