package com.lucoris.pulse.ingest.gdelt;

import java.time.LocalDate;

/**
 * Ergebnis eines GDELT-Tagesabrufs: eingefügte Zeilen je Roh-Tabelle sowie übersprungene Slices
 * (fehlend/404), beim Parsen verworfene Rohzeilen und die durch den Marktrelevanz-Filter vor dem
 * Speichern verworfenen GKG-Artikel.
 *
 * @param gkg         gespeicherte (marktrelevante) GKG-Artikel
 * @param gkgFiltered als nicht marktrelevant verworfene GKG-Artikel
 */
public record DayIngestReport(
        LocalDate day,
        long events,
        long mentions,
        long gkg,
        long gkgFiltered,
        long skippedSlices,
        long malformedRows) {

    /** Summe eingefügter Zeilen über alle drei Roh-Tabellen. */
    public long total() {
        return events + mentions + gkg;
    }
}
