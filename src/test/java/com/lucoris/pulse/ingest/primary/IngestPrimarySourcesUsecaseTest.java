package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.core.domain.PrimaryFeedItem;
import com.lucoris.pulse.core.domain.PrimarySourceState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reiner Unit-Test der Ingest-Schleife mit Persistenz — ohne Spring, Netz oder DB (Fake-Stores
 * als In-Memory-Maps). Gelesen wird ein Test-Manifest (NICHT die echte Registry), damit auch der
 * Ausfall einer Quelle prüfbar ist: 'aktiv-kaputt' hat einen nicht implementierten Handler.
 */
class IngestPrimarySourcesUsecaseTest {

    private static final Instant NOW = Instant.parse("2026-07-14T09:00:00Z");

    private final PrimarySourceManifestLoader registry = new PrimarySourceManifestLoader(
            JsonMapper.builder().build(), "primary-sources/test-manifest.json");

    private final RecordingAdapter rss = new RecordingAdapter();
    private final InMemoryFeedItemStore items = new InMemoryFeedItemStore();
    private final InMemorySourceStateStore states = new InMemorySourceStateStore();

    /** Nur generic_rss ist verdrahtet — 'aktiv-kaputt' läuft damit in die UnsupportedOperationException. */
    private final IngestPrimarySourcesUsecase usecase = new IngestPrimarySourcesUsecase(
            registry, new AdapterDispatcher(java.util.Map.of(GenericRssAdapter.HANDLER, rss)),
            items, states, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void onlyEnabledSourcesAreFetched() {
        usecase.runAll();

        assertThat(rss.received).containsExactly("aktiv-ok", "aktiv-danach");
        assertThat(rss.received).doesNotContain("abgeschaltet");
    }

    @Test
    void aFailingSourceDoesNotAbortTheRunAndIsRecordedAsFailure() {
        // 'aktiv-kaputt' steht MITTEN in der Liste und hat einen noch nicht gebauten Handler.
        // Die Quelle danach muss trotzdem abgerufen und gespeichert werden.
        PrimaryIngestReport report = usecase.runAll();

        assertThat(rss.received).containsExactly("aktiv-ok", "aktiv-danach");
        assertThat(report.results()).extracting(SourceRunResult::sourceId)
                .containsExactly("aktiv-ok", "aktiv-kaputt", "aktiv-danach");
        assertThat(report.failures()).singleElement().satisfies(f -> {
            assertThat(f.sourceId()).isEqualTo("aktiv-kaputt");
            assertThat(f.error()).contains("html_index");
        });

        // Der Fehlerzustand ist festgehalten, die Erfolge auch.
        assertThat(states.bySourceId.get("aktiv-kaputt").getConsecutiveFailures()).isEqualTo(1);
        assertThat(states.bySourceId.get("aktiv-kaputt").getLastError()).contains("html_index");
        assertThat(states.bySourceId.get("aktiv-ok").getConsecutiveFailures()).isZero();
        assertThat(states.bySourceId.get("aktiv-ok").getLastSuccessAt()).isEqualTo(NOW);
    }

    @Test
    void newItemsAreStoredWithDedupKeyAndCounted() {
        PrimaryIngestReport report = usecase.runAll();

        assertThat(report.totalFetched()).isEqualTo(2);
        assertThat(report.totalNew()).isEqualTo(2);
        assertThat(report.totalDeduped()).isZero();

        assertThat(items.byDedupKey).containsOnlyKeys(
                "https://example.org/aktiv-ok", "https://example.org/aktiv-danach");
        PrimaryFeedItem row = items.byDedupKey.get("https://example.org/aktiv-ok");
        assertThat(row.getSourceId()).isEqualTo("aktiv-ok");
        assertThat(row.getUrl()).isEqualTo("https://example.org/aktiv-ok?utm_source=rss");
        assertThat(row.getGuid()).isEqualTo("https://example.org/aktiv-ok");
    }

    @Test
    void aRepeatedRunIsIdempotent() {
        usecase.runAll();
        PrimaryIngestReport zweiter = usecase.runAll();

        assertThat(zweiter.totalNew()).isZero();
        assertThat(zweiter.totalDeduped()).isEqualTo(2);
        assertThat(items.byDedupKey).hasSize(2);
        assertThat(states.bySourceId.get("aktiv-ok").getLastDeduped()).isEqualTo(1);
    }

    @Test
    void inBatchDuplicatesCollapseToOneRow() {
        // Dieselbe Meldung zweimal im selben Feed-Abruf (z.B. Feed-Glitch) -> eine Zeile,
        // als Dublette gezählt.
        rss.duplicateEverything = true;

        PrimaryIngestReport report = usecase.runAll();

        assertThat(report.totalFetched()).isEqualTo(4);
        assertThat(report.totalNew()).isEqualTo(2);
        assertThat(report.totalDeduped()).isEqualTo(2);
        assertThat(items.byDedupKey).hasSize(2);
    }

    @Test
    void overlappingFeedsStoreTheSharedItemOnlyOnce() {
        // Der BMF-Fall: beide Quellen liefern dieselbe Meldung (gleiche guid). Die zweite Quelle
        // sieht sie als Dublette — gespeichert bleibt die Zeile der ERSTEN Quelle.
        rss.sharedGuid = "https://example.org/geteilte-meldung";

        PrimaryIngestReport report = usecase.runAll();

        assertThat(report.totalFetched()).isEqualTo(2);
        assertThat(report.totalNew()).isEqualTo(1);
        assertThat(report.totalDeduped()).isEqualTo(1);
        assertThat(items.byDedupKey).containsOnlyKeys("https://example.org/geteilte-meldung");
        assertThat(items.byDedupKey.get("https://example.org/geteilte-meldung").getSourceId())
                .isEqualTo("aktiv-ok");
    }

    /** Fake-Adapter: merkt sich die abgerufenen Quellen-IDs und liefert je Quelle eine Meldung. */
    private static final class RecordingAdapter implements SourceAdapter {
        private final List<String> received = new ArrayList<>();
        private boolean duplicateEverything;
        private String sharedGuid;

        @Override
        public List<FeedItem> fetch(IngestSource source) {
            received.add(source.id());
            String guid = sharedGuid != null ? sharedGuid : "https://example.org/" + source.id();
            FeedItem item = new FeedItem(
                    source.id(), source.institution(), "Titel " + source.id(),
                    guid + "?utm_source=rss", guid,
                    Instant.parse("2026-07-13T17:00:00Z"), null, "en",
                    Instant.parse("2026-07-14T09:00:00Z"), source.legalClass(), source.attribution());
            return duplicateEverything ? List.of(item, item) : List.of(item);
        }
    }

    private static final class InMemoryFeedItemStore implements FeedItemStore {
        private final Map<String, PrimaryFeedItem> byDedupKey = new LinkedHashMap<>();

        @Override
        public Set<String> existingDedupKeys(Collection<String> dedupKeys) {
            Set<String> found = new HashSet<>(dedupKeys);
            found.retainAll(byDedupKey.keySet());
            return found;
        }

        @Override
        public int insert(List<PrimaryFeedItem> rows) {
            for (PrimaryFeedItem row : rows) {
                if (byDedupKey.putIfAbsent(row.getDedupKey(), row) != null) {
                    throw new IllegalStateException("UNIQUE verletzt: " + row.getDedupKey());
                }
            }
            return rows.size();
        }
    }

    private static final class InMemorySourceStateStore implements SourceStateStore {
        private final Map<String, PrimarySourceState> bySourceId = new HashMap<>();

        @Override
        public Map<String, PrimarySourceState> loadAll() {
            return Map.copyOf(bySourceId);
        }

        @Override
        public void recordSuccess(String sourceId, Instant at, int fetched, int newItems, int deduped) {
            PrimarySourceState state = bySourceId.computeIfAbsent(sourceId, this::neu);
            state.setLastAttemptAt(at);
            state.setLastSuccessAt(at);
            state.setConsecutiveFailures(0);
            state.setLastError(null);
            state.setLastFetched(fetched);
            state.setLastNew(newItems);
            state.setLastDeduped(deduped);
        }

        @Override
        public void recordFailure(String sourceId, Instant at, String error) {
            PrimarySourceState state = bySourceId.computeIfAbsent(sourceId, this::neu);
            state.setLastAttemptAt(at);
            state.setConsecutiveFailures(state.getConsecutiveFailures() + 1);
            state.setLastError(error);
            state.setLastErrorAt(at);
        }

        private PrimarySourceState neu(String sourceId) {
            PrimarySourceState state = new PrimarySourceState();
            state.setSourceId(sourceId);
            return state;
        }
    }
}
