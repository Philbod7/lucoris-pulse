package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reiner Unit-Test der Ingest-Schleife — ohne Spring, Netz oder DB. Gelesen wird ein
 * Test-Manifest (NICHT die echte Registry), damit auch der Ausfall einer Quelle prüfbar ist.
 */
class IngestPrimarySourcesUsecaseTest {

    private final PrimarySourceManifestLoader registry = new PrimarySourceManifestLoader(
            JsonMapper.builder().build(), "primary-sources/test-manifest.json");

    private final RecordingAdapter rss = new RecordingAdapter();

    /** Nur generic_rss ist verdrahtet — 'sec_edgar' läuft damit in die UnsupportedOperationException. */
    private final IngestPrimarySourcesUsecase usecase = new IngestPrimarySourcesUsecase(
            registry, new AdapterDispatcher(java.util.Map.of(GenericRssAdapter.HANDLER, rss)));

    @Test
    void onlyEnabledSourcesAreFetched() {
        usecase.run();

        assertThat(rss.received).containsExactly("aktiv-ok", "aktiv-danach");
        assertThat(rss.received).doesNotContain("abgeschaltet");
    }

    @Test
    void aFailingSourceDoesNotAbortTheRun() {
        // 'aktiv-kaputt' steht MITTEN in der Liste und hat einen noch nicht gebauten Handler.
        // Die Quelle danach muss trotzdem abgerufen werden, und ihre Ereignisse müssen ankommen.
        List<PrimaryEvent> events = usecase.run();

        assertThat(rss.received).containsExactly("aktiv-ok", "aktiv-danach");
        assertThat(events).extracting(PrimaryEvent::sourceId)
                .containsExactly("aktiv-ok", "aktiv-danach");
    }

    @Test
    void eventsOfAllSourcesAreCollectedInOneList() {
        List<PrimaryEvent> events = usecase.run();

        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.url()).isNotBlank();
            assertThat(e.publishedAt()).isNotNull();
        });
    }

    /** Fake-Adapter: merkt sich die abgerufenen Quellen-IDs und liefert je Quelle ein Ereignis. */
    private static final class RecordingAdapter implements SourceAdapter {
        private final List<String> received = new ArrayList<>();

        @Override
        public List<PrimaryEvent> fetch(IngestSource source) {
            received.add(source.id());
            return List.of(new PrimaryEvent(
                    source.id(), source.institution(), "Titel " + source.id(),
                    "https://example.org/" + source.id(),
                    Instant.parse("2026-07-13T17:00:00Z"), null, "en",
                    Instant.parse("2026-07-14T09:00:00Z"), source.legalClass(), source.attribution()));
        }
    }
}
