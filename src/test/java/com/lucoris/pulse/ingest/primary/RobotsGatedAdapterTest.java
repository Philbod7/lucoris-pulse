package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import com.lucoris.pulse.ingest.primary.robots.SourceNotPermittedException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reiner Unit-Test des Sicherheitsnetzes an der Stelle, an der es greift: VOR dem Dispatcher —
 * ohne Spring, Netz oder DB.
 */
class RobotsGatedAdapterTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-14T09:00:00Z"), ZoneOffset.UTC);

    private final RecordingAdapter delegate = new RecordingAdapter();

    @Test
    void allowedSourceIsPassedThroughToTheDelegate() {
        RobotsGatedAdapter adapter = new RobotsGatedAdapter(
                delegate, intent -> RobotsGate.Decision.allow("robots.txt erlaubt es"), CLOCK);

        List<PrimaryEvent> events = adapter.fetch(source("ecb-press"));

        assertThat(delegate.received).containsExactly("ecb-press");
        assertThat(events).hasSize(1);
    }

    @Test
    void forbiddenSourceIsNeverFetchedAndFailsLoudly() {
        // Der eigentliche Zweck: der Delegat darf gar nicht erst laufen. Und ein Verbot wirft —
        // eine leere Liste sähe aus wie ein leerer Feed und das Verbot bliebe unsichtbar.
        RobotsGatedAdapter adapter = new RobotsGatedAdapter(
                delegate, intent -> RobotsGate.Decision.deny("robots.txt sperrt KI-Crawler [gptbot]"), CLOCK);

        assertThatExceptionOfType(SourceNotPermittedException.class)
                .isThrownBy(() -> adapter.fetch(source("boese-quelle")))
                .withMessageContaining("boese-quelle")
                .withMessageContaining("untersagt")
                .withMessageContaining("gptbot");

        assertThat(delegate.received).isEmpty(); // KEIN Abruf
    }

    @Test
    void theGateSeesTheSourceUrlNotTheRobotsUrl() {
        List<URI> geprueft = new ArrayList<>();
        RobotsGatedAdapter adapter = new RobotsGatedAdapter(delegate, intent -> {
            geprueft.add(intent.url());
            return RobotsGate.Decision.allow("ok");
        }, CLOCK);

        adapter.fetch(source("ecb-press"));

        assertThat(geprueft).containsExactly(URI.create("https://example.org/rss/press.xml"));
    }

    private static IngestSource source(String id) {
        return new IngestSource(
                id, "Institution", "central_bank", "EA", 1, List.of(),
                new Access("rss", "https://example.org/rss/press.xml", "rss2.0"),
                GenericRssAdapter.HANDLER, new Poll("interval", 300, null),
                true, "verified", "A", null, null, null);
    }

    /** Fake-Delegat: merkt sich, ob und womit er aufgerufen wurde. */
    private static final class RecordingAdapter implements SourceAdapter {
        private final List<String> received = new ArrayList<>();

        @Override
        public List<PrimaryEvent> fetch(IngestSource source) {
            received.add(source.id());
            return List.of(new PrimaryEvent(
                    source.id(), source.institution(), "Titel", "https://example.org/1",
                    Instant.parse("2026-07-13T17:00:00Z"), null, "en",
                    Instant.parse("2026-07-14T09:00:00Z"), source.legalClass(), source.attribution()));
        }
    }
}
