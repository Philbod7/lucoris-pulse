package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.core.domain.PrimaryFeedItem;
import com.lucoris.pulse.core.domain.PrimarySourceState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * Reiner Unit-Test des Poll-Ticks — ohne Spring, Netz oder DB. Test-Manifest: 'aktiv-ok' (300s),
 * 'aktiv-kaputt' (120s, Handler nicht implementiert), 'aktiv-danach' (300s), 'abgeschaltet'.
 */
class PollPrimarySourcesUsecaseTest {

    private static final Instant START = Instant.parse("2026-07-14T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final PrimarySourceManifestLoader registry = new PrimarySourceManifestLoader(
            JsonMapper.builder().build(), "primary-sources/test-manifest.json");
    private final RecordingAdapter rss = new RecordingAdapter();
    private final InMemoryFeedItemStore items = new InMemoryFeedItemStore();
    private final InMemorySourceStateStore states = new InMemorySourceStateStore();

    private final PollPrimarySourcesUsecase poller = new PollPrimarySourcesUsecase(
            registry, states,
            new PollSchedule(intent -> com.lucoris.pulse.ingest.primary.robots.RobotsGate.Decision.allow("ok")),
            new IngestPrimarySourcesUsecase(
                    registry, new AdapterDispatcher(Map.of(GenericRssAdapter.HANDLER, rss)),
                    items, states, clock),
            clock);

    @Test
    void firstTickFetchesEveryEnabledIntervalSourceAndFailuresDoNotAbort() {
        List<SourceRunResult> results = poller.tick();

        // Alle drei sind fällig (nie versucht); die kaputte MITTEN drin stoppt nichts.
        assertThat(results).extracting(SourceRunResult::sourceId)
                .containsExactly("aktiv-ok", "aktiv-kaputt", "aktiv-danach");
        assertThat(rss.received).containsExactly("aktiv-ok", "aktiv-danach");
        assertThat(states.bySourceId.get("aktiv-kaputt").getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void nothingIsDueImmediatelyAfterARun() {
        poller.tick();
        rss.received.clear();

        List<SourceRunResult> zweiter = poller.tick();

        assertThat(zweiter).isEmpty();
        assertThat(rss.received).isEmpty();
    }

    @Test
    void sourcesBecomeDueAgainAccordingToTheirOwnInterval() {
        poller.tick();
        rss.received.clear();

        // 121s später: nur 'aktiv-kaputt' (120s) ist wieder fällig — die 300s-Quellen nicht.
        clock.advance(Duration.ofSeconds(121));
        List<SourceRunResult> dritter = poller.tick();

        assertThat(dritter).extracting(SourceRunResult::sourceId).containsExactly("aktiv-kaputt");
        assertThat(rss.received).isEmpty(); // kaputt erreicht den RSS-Adapter nie

        // Weitere 180s (Summe 301s): jetzt sind auch die 300s-Quellen wieder dran.
        clock.advance(Duration.ofSeconds(180));
        List<SourceRunResult> vierter = poller.tick();

        assertThat(vierter).extracting(SourceRunResult::sourceId)
                .containsExactly("aktiv-ok", "aktiv-kaputt", "aktiv-danach");
        assertThat(vierter).filteredOn(r -> !r.failed())
                .allSatisfy(r -> assertThat(r.deduped()).isEqualTo(1)); // Wiederholung: alles Dublette
    }

    /** Verstellbare Uhr — der Tick soll Fälligkeit über die Zeitachse zeigen. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingAdapter implements SourceAdapter {
        private final List<String> received = new ArrayList<>();

        @Override
        public List<FeedItem> fetch(IngestSource source) {
            received.add(source.id());
            String url = "https://example.org/" + source.id();
            return List.of(new FeedItem(
                    source.id(), source.institution(), "Titel", url, url,
                    Instant.parse("2026-07-13T17:00:00Z"), null, "en",
                    Instant.parse("2026-07-14T09:00:00Z"), source.legalClass(), source.attribution()));
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
            rows.forEach(row -> byDedupKey.put(row.getDedupKey(), row));
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
