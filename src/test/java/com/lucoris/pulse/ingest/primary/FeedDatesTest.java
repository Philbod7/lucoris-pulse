package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Reiner Unit-Test der toleranten Datums-Kette — alles wird nach UTC normalisiert. */
class FeedDatesTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
        // RFC 1123 (RSS pubDate) — mit Offset, mit GMT, mit einstelligem Tag.
        "'Mon, 13 Jul 2026 19:00:00 +0200', 2026-07-13T17:00:00Z",
        "'Thu, 9 Jul 2026 19:00:00 GMT',    2026-07-09T19:00:00Z",
        // ISO mit Offset bzw. Zulu (Atom).
        "'2026-07-13T19:00:00+02:00',       2026-07-13T17:00:00Z",
        "'2026-07-13T17:00:00Z',            2026-07-13T17:00:00Z",
        // Ohne Zonenangabe -> als UTC gelesen (Annahme, aber deployment-unabhängig).
        "'2026-07-13T17:00:00',             2026-07-13T17:00:00Z",
        "'2026-07-10 06:15:00',             2026-07-10T06:15:00Z",
        "'2026-07-10 06:15',                2026-07-10T06:15:00Z",
        // Nur ein Datum -> Tagesbeginn UTC.
        "'2026-07-10',                      2026-07-10T00:00:00Z",
        // Umgebende Leerzeichen stören nicht.
        "'  2026-07-13T17:00:00Z  ',        2026-07-13T17:00:00Z",
    })
    void parsesTheCommonFeedDateFormatsToUtc(String raw, String expected) {
        assertThat(FeedDates.parse(raw)).contains(Instant.parse(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "gestern", "13.07.2026", "not a date at all"})
    void unparsableInputYieldsEmptyInsteadOfGuessing(String raw) {
        assertThat(FeedDates.parse(raw)).isEmpty();
    }

    @Test
    void localTimeIsReadAsUtcNotAsTheServersZone() {
        // Der eigentliche Punkt: dasselbe Ergebnis, egal in welcher Zeitzone der Server läuft.
        assertThat(FeedDates.parse("2026-07-13T12:00:00")).contains(Instant.parse("2026-07-13T12:00:00Z"));
    }
}
