package com.lucoris.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import com.lucoris.pulse.core.domain.IngestLog;
import com.lucoris.pulse.core.domain.UrlIndex;
import com.lucoris.pulse.ingest.gdelt.DayIngestReport;
import com.lucoris.pulse.ingest.gdelt.FirehoseStore;
import com.lucoris.pulse.ingest.gdelt.GdeltDataset;
import com.lucoris.pulse.ingest.gdelt.GdeltIngestService;
import com.lucoris.pulse.ingest.gdelt.GdeltSliceClient;
import com.lucoris.pulse.ingest.gdelt.MissingEventRef;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Beweist das Zwei-Phasen-Event-Modell (Kopplung + Backfill):
 * <ul>
 *   <li>Phase 1: nur zum relevanten Artikel {@code U1} gehörende Mentions bleiben (die zu {@code U2}
 *       werden verworfen).</li>
 *   <li>Phase 2: fehlende Events werden aufgelöst — Event 100 aus dem AKTUELLEN Slice (einmal
 *       geladen), Event 900 per Backfill aus einem älteren Slice, Event 777 als Stub, weil sein
 *       Slice auch nach 3 Versuchen nicht lesbar ist.</li>
 * </ul>
 * Kein Netz, keine DB-Writes (Client + Store durch Test-Doubles ersetzt).
 */
@ActiveProfiles("ingest")
@TestPropertySource(properties = {
        "lucoris.ingest.gdelt.market-relevant-theme-prefixes=TESTREL_",
        "lucoris.ingest.gdelt.log-theme-histogram=false",
        "lucoris.ingest.gdelt.filter-linked-events-and-mentions=true",
        "lucoris.ingest.gdelt.event-backfill-retries=3"
})
@Import(GdeltLinkedFilterIT.Stubs.class)
class GdeltLinkedFilterIT extends AbstractPostgresIT {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final LocalDateTime T0 = DAY.atStartOfDay();               // aktueller Slice
    private static final LocalDateTime S_OLD = LocalDateTime.of(2026, 7, 9, 12, 0);   // lesbar
    private static final LocalDateTime S_MISSING = LocalDateTime.of(2026, 7, 9, 6, 0); // nie lesbar
    private static final String URL_REL = "https://example.com/U1";
    private static final String URL_IRR = "https://example.com/U2";

    @Autowired GdeltIngestService service;
    @Autowired CapturingStore store;
    @Autowired StubClient client;

    @BeforeEach
    void reset() {
        store.clear();
        client.reset();
    }

    @Test
    void resolvesEventsFromCurrentSliceBackfillAndStub() {
        DayIngestReport report = service.ingestDay(DAY);

        // Phase 1: nur der relevante Artikel und seine gekoppelten Mentions.
        assertThat(store.gkg).extracting(GdeltGkg::getGkgRecordId).containsExactly("GKG-U1");
        assertThat(store.mentions).extracting(GdeltMention::getGlobalEventId)
                .containsExactlyInAnyOrder(100L, 900L, 777L); // m2 (URL_IRR) verworfen

        // Phase 2: Event aus aktuellem Slice + Backfill + Stub.
        assertThat(store.events).extracting(GdeltEvent::getGlobalEventId)
                .containsExactlyInAnyOrder(100L, 900L, 777L);
        GdeltEvent stub = store.events.stream().filter(e -> e.getGlobalEventId() == 777L).findFirst().orElseThrow();
        assertThat(stub.getSourceUrl()).isNull();
        assertThat(stub.getDateAdded()).isEqualTo(S_MISSING.toInstant(ZoneOffset.UTC));

        // URL-Index: S-Zeilen aus den gekoppelten Mentions (URL_REL, mit echter global_event_id),
        // P-Zeilen aus Events mit source_url (Stub 777 hat null source_url -> keine P-Zeile).
        assertThat(store.urlIndex).hasSize(5);
        assertThat(store.urlIndex).filteredOn(u -> u.getSourceFlag().equals("S"))
                .extracting(UrlIndex::getGlobalEventId)
                .containsExactlyInAnyOrder(100L, 900L, 777L);
        assertThat(store.urlIndex).filteredOn(u -> u.getSourceFlag().equals("S"))
                .extracting(UrlIndex::getUrl).containsOnly(URL_REL);
        assertThat(store.urlIndex).filteredOn(u -> u.getSourceFlag().equals("P"))
                .extracting(UrlIndex::getGlobalEventId)
                .containsExactlyInAnyOrder(100L, 900L); // 777 (Stub, source_url null) fehlt

        assertThat(report.events()).isEqualTo(3);
        assertThat(report.eventsBackfilled()).isEqualTo(1);   // 900
        assertThat(report.eventsStubbed()).isEqualTo(1);      // 777
        assertThat(report.eventBackfillSlices()).isEqualTo(2); // S_OLD + S_MISSING
        assertThat(report.mentionsFiltered()).isEqualTo(1);   // m2
        assertThat(report.gkgFiltered()).isEqualTo(1);        // U2

        // Abruf-Zählung: aktueller Slice EINMAL, älterer lesbarer EINMAL, fehlender 3x (Retries).
        assertThat(client.eventDownloads(T0)).isEqualTo(1);
        assertThat(client.eventDownloads(S_OLD)).isEqualTo(1);
        assertThat(client.eventDownloads(S_MISSING)).isEqualTo(3);
    }

    @Test
    void secondRunSkipsAlreadyIngestedSlices() {
        service.ingestDay(DAY);
        int gkg1 = store.gkg.size();
        int mentions1 = store.mentions.size();
        int events1 = store.events.size();

        DayIngestReport second = service.ingestDay(DAY);

        // GKG/Mentions/Events werden NICHT erneut geschrieben (keine Constraint-Verletzung).
        assertThat(store.gkg).hasSize(gkg1);
        assertThat(store.mentions).hasSize(mentions1);
        assertThat(store.events).hasSize(events1);
        // Nur der Slice mit GKG (T0) stand in ingest_log -> übersprungen.
        assertThat(second.slicesAlreadyProcessed()).isEqualTo(1);
        assertThat(second.gkg()).isZero();
        assertThat(second.mentions()).isZero();
        assertThat(second.events()).isZero();
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        StubClient stubClient() {
            return new StubClient();
        }

        @Bean
        @Primary
        CapturingStore capturingStore() {
            return new CapturingStore();
        }
    }

    /** Liefert je Datensatz/Slice kanonische Testdaten und zählt EVENTS-Abrufe je Slice. */
    static class StubClient implements GdeltSliceClient {
        private final Map<String, Integer> eventDownloads = new HashMap<>();

        int eventDownloads(LocalDateTime slice) {
            return eventDownloads.getOrDefault(slice.format(STAMP), 0);
        }

        void reset() {
            eventDownloads.clear();
        }

        @Override
        public Optional<List<String[]>> download(GdeltDataset dataset, LocalDateTime sliceStartUtc) {
            if (dataset == GdeltDataset.EVENTS) {
                eventDownloads.merge(sliceStartUtc.format(STAMP), 1, Integer::sum);
                if (sliceStartUtc.equals(T0)) {
                    return Optional.of(List.<String[]>of(eventRow(100L, T0)));
                }
                if (sliceStartUtc.equals(S_OLD)) {
                    return Optional.of(List.<String[]>of(eventRow(900L, S_OLD)));
                }
                return Optional.empty(); // u.a. S_MISSING: nie lesbar
            }
            if (dataset == GdeltDataset.GKG && sliceStartUtc.equals(T0)) {
                return Optional.of(List.of(
                        gkgRow("GKG-U1", URL_REL, "TESTREL_MARKET,10"),   // relevant
                        gkgRow("GKG-U2", URL_IRR, "ECON_STOCKMARKET,10")  // irrelevant
                ));
            }
            if (dataset == GdeltDataset.MENTIONS && sliceStartUtc.equals(T0)) {
                return Optional.of(List.of(
                        mentionRow(100L, T0, URL_REL),        // Event im aktuellen Slice
                        mentionRow(200L, T0, URL_IRR),        // -> verworfen (Artikel nicht relevant)
                        mentionRow(900L, S_OLD, URL_REL),     // Event in älterem, lesbarem Slice
                        mentionRow(777L, S_MISSING, URL_REL)  // Event in nie lesbarem Slice -> Stub
                ));
            }
            return Optional.empty();
        }

        private static String[] gkgRow(String id, String url, String themes) {
            String[] c = filled(27);
            c[0] = id;
            c[1] = T0.format(STAMP);
            c[3] = "example.com";
            c[4] = url;
            c[8] = themes;
            return c;
        }

        private static String[] mentionRow(long eventId, LocalDateTime eventTime, String url) {
            String[] c = filled(16);
            c[0] = Long.toString(eventId);
            c[1] = eventTime.format(STAMP);  // EventTimeDate = Slice des Events
            c[2] = T0.format(STAMP);         // MentionTimeDate = aktueller Slice
            c[4] = "example.com";
            c[5] = url;
            return c;
        }

        private static String[] eventRow(long eventId, LocalDateTime dateAdded) {
            String[] c = filled(61);
            c[0] = Long.toString(eventId);
            c[59] = dateAdded.format(STAMP);
            c[60] = "https://example.com/e" + eventId;
            return c;
        }

        private static String[] filled(int size) {
            String[] c = new String[size];
            Arrays.fill(c, "");
            return c;
        }
    }

    /** Fängt geschriebene Zeilen ab und beantwortet {@code findMissingEventRefs} aus dem Erfassten. */
    static class CapturingStore implements FirehoseStore {
        final List<GdeltEvent> events = new ArrayList<>();
        final List<GdeltMention> mentions = new ArrayList<>();
        final List<GdeltGkg> gkg = new ArrayList<>();
        final List<UrlIndex> urlIndex = new ArrayList<>();
        final Set<String> processedFiles = new HashSet<>();

        void clear() {
            events.clear();
            mentions.clear();
            gkg.clear();
            urlIndex.clear();
            processedFiles.clear();
        }

        @Override
        public int insertEvents(List<GdeltEvent> rows) {
            events.addAll(rows);
            return rows.size();
        }

        @Override
        public int insertMentions(List<GdeltMention> rows) {
            mentions.addAll(rows);
            return rows.size();
        }

        @Override
        public int insertGkg(List<GdeltGkg> rows) {
            gkg.addAll(rows);
            return rows.size();
        }

        @Override
        public int insertAtomic(List<?> rows) {
            for (Object row : rows) {
                if (row instanceof GdeltGkg g) {
                    gkg.add(g);
                } else if (row instanceof GdeltMention m) {
                    mentions.add(m);
                } else if (row instanceof GdeltEvent e) {
                    events.add(e);
                } else if (row instanceof UrlIndex u) {
                    urlIndex.add(u);
                } else if (row instanceof IngestLog logEntry) {
                    processedFiles.add(logEntry.getFilename());
                }
            }
            return rows.size();
        }

        @Override
        public boolean isFileProcessed(String filename) {
            return processedFiles.contains(filename);
        }

        @Override
        public List<MissingEventRef> findMissingEventRefs(Instant sliceStart, Instant sliceEndExcl) {
            Set<Long> existing = new HashSet<>();
            for (GdeltEvent e : events) {
                existing.add(e.getGlobalEventId());
            }
            Map<Long, Instant> missing = new LinkedHashMap<>();
            for (GdeltMention m : mentions) {
                Instant mt = m.getMentionTimeDate();
                if (mt == null || mt.isBefore(sliceStart) || !mt.isBefore(sliceEndExcl)) {
                    continue;
                }
                if (m.getEventTimeDate() == null || existing.contains(m.getGlobalEventId())) {
                    continue;
                }
                missing.putIfAbsent(m.getGlobalEventId(), m.getEventTimeDate());
            }
            return missing.entrySet().stream()
                    .map(e -> new MissingEventRef(e.getKey(), e.getValue()))
                    .toList();
        }
    }
}
