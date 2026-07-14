package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.core.domain.PrimarySourceState;
import com.lucoris.pulse.ingest.primary.robots.FetchIntent;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Reiner Unit-Test der Fälligkeitsentscheidung — ohne Spring, Netz oder DB. */
class PollScheduleTest {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    /** Gate ohne Crawl-delay — für alle Tests, die keinen brauchen. */
    private final PollSchedule schedule = new PollSchedule(intent -> RobotsGate.Decision.allow("ok"));

    @Test
    void neverAttemptedSourceIsDue() {
        assertThat(schedule.isDue(interval(300), null, NOW)).isTrue();

        // Auch mit Zustandszeile, aber ohne je einen Versuch (kann nach Hand-Eingriff vorkommen).
        assertThat(schedule.isDue(interval(300), new PrimarySourceState(), NOW)).isTrue();
    }

    @Test
    void withinTheIntervalTheSourceIsNotDue() {
        assertThat(schedule.isDue(interval(300), attemptedAt(NOW.minusSeconds(299)), NOW)).isFalse();
    }

    @Test
    void atAndAfterTheIntervalBoundaryTheSourceIsDue() {
        assertThat(schedule.isDue(interval(300), attemptedAt(NOW.minusSeconds(300)), NOW)).isTrue();
        assertThat(schedule.isDue(interval(300), attemptedAt(NOW.minusSeconds(301)), NOW)).isTrue();
    }

    @Test
    void aRestartDoesNotResetTheRhythm() {
        // Der Anker ist der PERSISTIERTE letzte Versuch — direkt nach einem Neustart ist eine
        // gerade erst abgerufene Quelle deshalb NICHT fällig (kein Hämmern bei jedem Deploy).
        assertThat(schedule.isDue(interval(300), attemptedAt(NOW.minusSeconds(10)), NOW)).isFalse();
    }

    @Test
    void crawlDelayStretchesTheEffectiveInterval() {
        // robots.txt sagt Crawl-delay 600 — das Manifest-Intervall 300 darf das nicht unterlaufen:
        // wer sich auf das Wohlwollen des Herausgebers beruft, verletzt nicht seine Abrufgrenze.
        RobotsGate mitDelay = new RobotsGate() {
            @Override
            public Decision check(FetchIntent intent) {
                return Decision.allow("ok");
            }

            @Override
            public Optional<Integer> crawlDelaySeconds(URI url) {
                return Optional.of(600);
            }
        };
        PollSchedule gebremst = new PollSchedule(mitDelay);

        assertThat(gebremst.isDue(interval(300), attemptedAt(NOW.minusSeconds(400)), NOW)).isFalse();
        assertThat(gebremst.isDue(interval(300), attemptedAt(NOW.minusSeconds(600)), NOW)).isTrue();
    }

    @Test
    void calendarSourcesAreNeverDueForTheIntervalPoller() {
        IngestSource destatis = source(new Poll("calendar", null, "https://example.org/kalender"));

        assertThat(schedule.isDue(destatis, null, NOW)).isFalse();
        assertThat(schedule.isDue(destatis, attemptedAt(NOW.minusSeconds(999_999)), NOW)).isFalse();
    }

    @Test
    void intervalWithoutSecondsIsAMisconfigurationNotAHotLoop() {
        IngestSource kaputt = source(new Poll("interval", null, null));

        assertThat(schedule.isDue(kaputt, null, NOW)).isFalse();
    }

    private static IngestSource interval(int seconds) {
        return source(new Poll("interval", seconds, null));
    }

    private static IngestSource source(Poll poll) {
        return new IngestSource(
                "quelle", "Institution", "central_bank", "EA", 1, List.of(),
                new Access("rss", "https://example.org/f.xml", "rss2.0"),
                GenericRssAdapter.HANDLER, poll,
                true, "verified", "A", null, null, null);
    }

    private static PrimarySourceState attemptedAt(Instant at) {
        PrimarySourceState state = new PrimarySourceState();
        state.setSourceId("quelle");
        state.setLastAttemptAt(at);
        return state;
    }
}
