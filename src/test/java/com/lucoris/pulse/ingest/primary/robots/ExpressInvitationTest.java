package com.lucoris.pulse.ingest.primary.robots;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Reiner Unit-Test der Evidenz-Prüfung (Bedingung b) — ohne Spring, Netz oder DB. */
class ExpressInvitationTest {

    private static final Duration HALBES_JAHR = Duration.ofDays(180);

    private static ExpressInvitation gueltig() {
        return new ExpressInvitation(
                "https://example.org/rss.html",
                "Kopieren Sie den Link der RSS-Datei in Ihren RSS-Reader.",
                "2026-07-13",
                null);
    }

    @Test
    void completeInvitationNeedsPageUrlWordingAndAReadableDate() {
        assertThat(gueltig().complete()).isTrue();
        assertThat(gueltig().retrievedDate()).contains(LocalDate.of(2026, 7, 13));
    }

    @ParameterizedTest(name = "[{index}] unvollständig: {3}")
    @CsvSource(nullValues = "NULL", value = {
        "NULL,                      wording, 2026-07-13, 'page_url fehlt'",
        "https://example.org/x.html, NULL,   2026-07-13, 'wording fehlt'",
        "https://example.org/x.html, wording, NULL,      'retrieved fehlt'",
        "'',                        wording, 2026-07-13, 'page_url leer'",
        "https://example.org/x.html, '',     2026-07-13, 'wording leer'",
        "https://example.org/x.html, wording, gestern,   'retrieved unlesbar'",
        "https://example.org/x.html, wording, 13.07.2026,'retrieved kein ISO-8601'",
    })
    void incompleteEvidenceIsNeverComplete(String pageUrl, String wording, String retrieved, String fall) {
        ExpressInvitation unvollstaendig = new ExpressInvitation(pageUrl, wording, retrieved, null);

        assertThat(unvollstaendig.complete()).as(fall).isFalse();
    }

    @Test
    void evidenceGoesStale() {
        // Der eigentliche Punkt: eine Feststellung von 2026 trägt einen Abruf 2031 nicht mehr.
        ExpressInvitation einladung = gueltig(); // festgestellt 2026-07-13

        assertThat(einladung.olderThan(HALBES_JAHR, Instant.parse("2026-08-01T00:00:00Z"))).isFalse();
        assertThat(einladung.olderThan(HALBES_JAHR, Instant.parse("2027-07-13T00:00:00Z"))).isTrue();
        assertThat(einladung.olderThan(HALBES_JAHR, Instant.parse("2031-01-01T00:00:00Z"))).isTrue();
    }

    @Test
    void unreadableDateCountsAsStaleNotAsFresh() {
        // Kein lesbares Datum = keine belastbare Feststellung. Die sichere Richtung ist "veraltet".
        ExpressInvitation ohneDatum =
                new ExpressInvitation("https://example.org/x.html", "wording", "irgendwann", null);

        assertThat(ohneDatum.olderThan(HALBES_JAHR, Instant.parse("2026-07-13T00:00:00Z"))).isTrue();
    }
}
