package com.lucoris.pulse.ingest.gdelt;

import java.time.LocalDate;

/**
 * Ergebnis eines GDELT-Tagesabrufs: eingefügte Zeilen je Roh-Tabelle, übersprungene Slices
 * (fehlend/404), beim Parsen verworfene Rohzeilen, durch Filter/Kopplung verworfene Zeilen sowie
 * Kennzahlen der Event-Auflösung (Phase 2).
 *
 * @param events             gespeicherte Events gesamt (aktueller Slice + Backfill + Stubs)
 * @param gkg                gespeicherte (marktrelevante) GKG-Artikel
 * @param eventsBackfilled   aus älteren Export-Slices nachgeladene Events
 * @param eventsStubbed      als Stub angelegte Events (Slice/Ereignis nicht auffindbar)
 * @param eventBackfillSlices Anzahl älterer Export-Slices, die für den Backfill geholt wurden
 * @param mentionsFiltered   verworfene Mentions (nicht an einen relevanten Artikel gekoppelt)
 * @param gkgFiltered        als nicht marktrelevant verworfene GKG-Artikel
 * @param slicesAlreadyProcessed laut {@code ingest_log} bereits eingelesene und übersprungene Slices
 */
public record DayIngestReport(
        LocalDate day,
        long events,
        long mentions,
        long gkg,
        long eventsBackfilled,
        long eventsStubbed,
        long eventBackfillSlices,
        long mentionsFiltered,
        long gkgFiltered,
        long slicesAlreadyProcessed,
        long skippedSlices,
        long malformedRows) {

    /** Summe eingefügter Zeilen über alle drei Roh-Tabellen. */
    public long total() {
        return events + mentions + gkg;
    }
}
