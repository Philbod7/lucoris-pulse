package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reiner Unit-Test des Handler-Routings — ohne Spring, Netz oder DB. */
class AdapterDispatcherTest {

    private final RecordingAdapter rss = new RecordingAdapter();
    private final AdapterDispatcher dispatcher =
            new AdapterDispatcher(Map.of(GenericRssAdapter.HANDLER, rss));

    @Test
    void genericRssIsRoutedToTheRssAdapter() {
        IngestSource ecb = source("ecb-press", "generic_rss");

        List<FeedItem> events = dispatcher.fetch(ecb);

        assertThat(rss.received).containsExactly(ecb);
        assertThat(events).hasSize(1);
    }

    @Test
    void unknownHandlerFailsLoudlyNamingSourceAndHandler() {
        // html_index ist im Manifest bereits vorgemerkt, aber noch nicht gebaut (sec_edgar ist es
        // inzwischen). Stilles Überspringen wäre ein unsichtbares Datenloch — also: laut scheitern.
        IngestSource landing = source("us-state", "html_index");

        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> dispatcher.fetch(landing))
                .withMessageContaining("html_index")
                .withMessageContaining("us-state")
                .withMessageContaining("noch nicht implementiert")
                .withMessageContaining("generic_rss"); // was es stattdessen gibt

        assertThat(rss.received).isEmpty();
    }

    @Test
    void handlersAreExposedForDiagnostics() {
        assertThat(dispatcher.handlers()).containsExactly("generic_rss");
    }

    private static IngestSource source(String id, String handler) {
        return new IngestSource(
                id, "Institution", "central_bank", "EA", 1, List.of(),
                new Access("rss", "https://example.org/f.xml", "rss2.0"),
                handler, new Poll("interval", 300, null),
                true, "verified", "A", null, null, null);
    }

    /** Fake-Adapter: merkt sich, womit er aufgerufen wurde, und liefert genau ein Ereignis. */
    private static final class RecordingAdapter implements SourceAdapter {
        private final List<IngestSource> received = new java.util.ArrayList<>();

        @Override
        public List<FeedItem> fetch(IngestSource source) {
            received.add(source);
            return List.of(new FeedItem(
                    source.id(), source.institution(), "Titel", "https://example.org/1", null,
                    Instant.parse("2026-07-13T17:00:00Z"), null, "en",
                    Instant.parse("2026-07-14T09:00:00Z"), source.legalClass(), source.attribution()));
        }
    }
}
