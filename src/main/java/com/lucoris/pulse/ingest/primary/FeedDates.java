package com.lucoris.pulse.ingest.primary;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tolerantes Parsen von Feed-Datumsangaben zu einem UTC-{@link Instant}.
 *
 * <p>Rome deckt RFC-822 und W3C-DateTime bereits ab. Diese Kette ist der Auffangnetz für alles,
 * woran Rome scheitert — Feeds sind in der Praxis unsauber. Reihenfolge: zuerst die Formate MIT
 * Zeitzone, dann die ohne.
 *
 * <p><strong>Formate ohne Zonenangabe werden als UTC gelesen.</strong> Das ist eine Annahme, keine
 * Wahrheit — aber die einzige, die nicht von der Zeitzone des laufenden Servers abhängt und damit
 * denselben Feed je nach Deployment anders interpretiert.
 */
public final class FeedDates {

    /** Formate mit Zonenangabe — der Zeitpunkt steht damit eindeutig fest. */
    private static final List<DateTimeFormatter> WITH_ZONE = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME, // "Mon, 13 Jul 2026 19:00:00 +0200" (RSS pubDate)
            DateTimeFormatter.ISO_OFFSET_DATE_TIME, // "2026-07-13T19:00:00+02:00" (Atom)
            DateTimeFormatter.ISO_INSTANT); // "2026-07-13T17:00:00Z"

    /** Datum + Uhrzeit ohne Zone — als UTC gelesen. */
    private static final List<DateTimeFormatter> LOCAL_DATE_TIME = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME, // "2026-07-13T19:00:00"
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]", Locale.ROOT)); // Leerzeichen statt 'T'

    /** Nur ein Datum — als Tagesbeginn UTC gelesen. */
    private static final DateTimeFormatter LOCAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private FeedDates() {}

    /**
     * @param raw die Rohangabe aus dem Feed ({@code pubDate}, {@code published}, {@code updated},
     *            {@code dc:date}); darf {@code null} oder leer sein
     * @return der Zeitpunkt in UTC, oder {@link Optional#empty()}, wenn kein Format greift
     */
    public static Optional<Instant> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();

        for (DateTimeFormatter format : WITH_ZONE) {
            try {
                return Optional.of(Instant.from(format.parse(text)));
            } catch (DateTimeException ignored) {
                // nächstes Format probieren
            }
        }
        for (DateTimeFormatter format : LOCAL_DATE_TIME) {
            try {
                return Optional.of(LocalDateTime.parse(text, format).toInstant(ZoneOffset.UTC));
            } catch (DateTimeException ignored) {
                // nächstes Format probieren
            }
        }
        try {
            return Optional.of(LocalDate.parse(text, LOCAL_DATE).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeException ignored) {
            return Optional.empty();
        }
    }
}
