package com.lucoris.pulse.ingest.gdelt;

import java.time.LocalDate;
import java.util.List;

/**
 * Ergebnis eines GDELT-Bereichsabrufs über den halboffenen UTC-Bereich {@code [von, bis)}:
 * die Tages-Reports der eingelesenen Tage plus Aggregat-Summen über den gesamten Bereich.
 *
 * @param vonInclusive erster eingelesener Tag (inklusive)
 * @param bisExclusive exklusive Obergrenze (erster NICHT mehr eingelesener Tag)
 * @param days         Report je eingelesenem Tag, chronologisch aufsteigend
 */
public record RangeIngestReport(
        LocalDate vonInclusive,
        LocalDate bisExclusive,
        List<DayIngestReport> days) {

    public RangeIngestReport {
        days = List.copyOf(days); // defensiv, unveränderlich
    }

    /** Anzahl eingelesener Tage im Bereich. */
    public int dayCount() {
        return days.size();
    }

    /** Summe gespeicherter Events über alle Tage. */
    public long events() {
        return days.stream().mapToLong(DayIngestReport::events).sum();
    }

    /** Summe gespeicherter Mentions über alle Tage. */
    public long mentions() {
        return days.stream().mapToLong(DayIngestReport::mentions).sum();
    }

    /** Summe gespeicherter (marktrelevanter) GKG-Artikel über alle Tage. */
    public long gkg() {
        return days.stream().mapToLong(DayIngestReport::gkg).sum();
    }

    /** Summe eingefügter Zeilen über alle drei Roh-Tabellen und alle Tage. */
    public long total() {
        return days.stream().mapToLong(DayIngestReport::total).sum();
    }
}
