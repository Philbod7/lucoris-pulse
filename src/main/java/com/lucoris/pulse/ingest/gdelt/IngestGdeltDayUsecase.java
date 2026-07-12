package com.lucoris.pulse.ingest.gdelt;

import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Geschäftslogik des GDELT-Tagesabrufs (reines POJO, ohne Spring). Iteriert einen UTC-Tag
 * chronologisch in 96 15-Minuten-Slices (00:00 bis 23:45).
 *
 * <p>Je Slice läuft die Verarbeitung in ZWEI Phasen (bei aktivierter Kopplung):
 * <ol>
 *   <li><b>Phase 1</b> — relevante {@link GdeltGkg} (Themen-Filter) und daran gekoppelte
 *       {@link GdeltMention} werden geschrieben und committed. Events werden hier NICHT geschrieben.</li>
 *   <li><b>Phase 2</b> — über die committeten Mentions dieses Slices werden per SQL die fehlenden
 *       Events ermittelt und aus ihren {@code eventTimeDate}-Slices aufgelöst: der aktuelle Slice
 *       wird nur einmal geladen; ältere Slices werden gebündelt (jeder einmal, mit Retries) geholt.
 *       Nicht auffindbare Events werden als Stub angelegt (Stabilität). Erst hier werden Events
 *       geschrieben.</li>
 * </ol>
 *
 * <p>Fehlende Slices (404) werden übersprungen; nicht parsebare Rohzeilen werden verworfen und
 * gezählt, ohne den Tageslauf abzubrechen.
 */
public final class IngestGdeltDayUsecase {

    private static final Logger log = LoggerFactory.getLogger(IngestGdeltDayUsecase.class);
    private static final int SLICES_PER_DAY = 96;
    private static final int SLICE_MINUTES = 15;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final GdeltSliceClient client;
    private final FirehoseStore store;
    private final GdeltEventRowMapper eventMapper;
    private final GdeltMentionRowMapper mentionMapper;
    private final GdeltGkgRowMapper gkgMapper;
    private final MarketRelevanceFilter marketRelevanceFilter;
    private final boolean logThemeHistogram;
    private final boolean filterLinkedEventsAndMentions;
    private final int eventBackfillRetries;

    public IngestGdeltDayUsecase(
            GdeltSliceClient client,
            FirehoseStore store,
            GdeltEventRowMapper eventMapper,
            GdeltMentionRowMapper mentionMapper,
            GdeltGkgRowMapper gkgMapper,
            MarketRelevanceFilter marketRelevanceFilter,
            boolean logThemeHistogram,
            boolean filterLinkedEventsAndMentions,
            int eventBackfillRetries) {
        this.client = client;
        this.store = store;
        this.eventMapper = eventMapper;
        this.mentionMapper = mentionMapper;
        this.gkgMapper = gkgMapper;
        this.marketRelevanceFilter = marketRelevanceFilter;
        this.logThemeHistogram = logThemeHistogram;
        this.filterLinkedEventsAndMentions = filterLinkedEventsAndMentions;
        this.eventBackfillRetries = Math.max(1, eventBackfillRetries);
    }

    /**
     * Ruft alle Slices des angegebenen UTC-Tages chronologisch ab und schreibt sie fortlaufend
     * in die DB.
     *
     * @param dayUtc der abzurufende Tag (als UTC-Kalendertag interpretiert)
     * @return Kennzahlen des Laufs
     */
    public DayIngestReport ingestDay(LocalDate dayUtc) {
        LocalDateTime dayStart = dayUtc.atStartOfDay(); // 00:00:00 UTC
        Counters counters = new Counters();
        ThemeHistogram histogram = new ThemeHistogram();
        long events = 0;
        long mentions = 0;
        long gkg = 0;

        for (int i = 0; i < SLICES_PER_DAY; i++) {
            LocalDateTime slice = dayStart.plusMinutes((long) SLICE_MINUTES * i); // 00:00 .. 23:45
            // Phase 1: relevante GKG + gekoppelte Mentions schreiben und committen.
            SliceGkg gkgResult = fetchFilterWriteGkg(slice, counters, histogram);
            gkg += gkgResult.written();
            mentions += fetchWriteMentions(slice, gkgResult.relevantUrls(), counters);
            // Phase 2: Events auflösen (nur bei Kopplung) bzw. Fallback (alle Events vorwärts).
            if (filterLinkedEventsAndMentions) {
                events += resolveSliceEvents(slice, counters);
            } else {
                events += fetchWriteAllEvents(slice, counters);
            }
        }

        DayIngestReport report = new DayIngestReport(
                dayUtc, events, mentions, gkg,
                counters.eventsBackfilled, counters.eventsStubbed, counters.eventBackfillSlices,
                counters.mentionsFiltered, counters.gkgFiltered,
                counters.skippedSlices, counters.malformedRows);
        log.info(
                "GDELT-Tagesabruf {} abgeschlossen: events={} (ausSlice={}, backfill={}, stubs={}), "
                        + "mentions={}, gkg(marktrelevant)={}, verworfen mentions={}/gkg={}, "
                        + "ältereSlices={}, übersprungeneSlices={}, verworfeneZeilen={}",
                dayUtc, events, counters.eventsFromCurrentSlice, counters.eventsBackfilled,
                counters.eventsStubbed, mentions, gkg, counters.mentionsFiltered, counters.gkgFiltered,
                counters.eventBackfillSlices, counters.skippedSlices, counters.malformedRows);
        if (logThemeHistogram) {
            logThemeHistogram(dayUtc, histogram);
        }
        return report;
    }

    /**
     * GKG-Slice: abrufen -> mappen -> Marktrelevanz-Filter (VOR dem Speichern) -> nur relevante
     * Artikel schreiben. Loggt je File eine Statistik und sammelt die URLs (document_identifier)
     * der behaltenen Artikel für die Mentions-Kopplung.
     */
    private SliceGkg fetchFilterWriteGkg(LocalDateTime slice, Counters counters, ThemeHistogram histogram) {
        Optional<List<String[]>> raw = client.download(GdeltDataset.GKG, slice);
        if (raw.isEmpty()) {
            counters.skippedSlices++;
            return new SliceGkg(0, Set.of());
        }
        List<GdeltGkg> parsed = mapRows(raw.get(), gkgMapper::map, counters);
        List<GdeltGkg> relevant = new ArrayList<>(parsed.size());
        Set<String> relevantUrls = new HashSet<>();
        for (GdeltGkg gkg : parsed) {
            histogram.addArticle(GdeltThemes.codes(gkg.getV2Themes())); // Statistik über ALLE Artikel
            if (marketRelevanceFilter.isRelevant(gkg)) {
                relevant.add(gkg);
                if (gkg.getDocumentIdentifier() != null) {
                    relevantUrls.add(gkg.getDocumentIdentifier());
                }
            }
        }
        int dropped = parsed.size() - relevant.size();
        counters.gkgFiltered += dropped;
        log.info("GKG-Slice {}: {} Artikel geparst, {} marktrelevant behalten, {} verworfen",
                stamp(slice), parsed.size(), relevant.size(), dropped);
        return new SliceGkg(store.insertGkg(relevant), relevantUrls);
    }

    /**
     * Mentions-Slice: abrufen -> mappen -> (falls Kopplung aktiv) nur behalten, deren
     * {@code mention_identifier} auf einen behaltenen Artikel zeigt -> schreiben und committen.
     *
     * @return Anzahl geschriebener Mentions
     */
    private long fetchWriteMentions(LocalDateTime slice, Set<String> relevantUrls, Counters counters) {
        Optional<List<String[]>> raw = client.download(GdeltDataset.MENTIONS, slice);
        if (raw.isEmpty()) {
            counters.skippedSlices++;
            return 0;
        }
        List<GdeltMention> parsed = mapRows(raw.get(), mentionMapper::map, counters);
        List<GdeltMention> kept;
        if (filterLinkedEventsAndMentions) {
            kept = new ArrayList<>(parsed.size());
            for (GdeltMention m : parsed) {
                if (relevantUrls.contains(m.getMentionIdentifier())) {
                    kept.add(m);
                }
            }
            int dropped = parsed.size() - kept.size();
            counters.mentionsFiltered += dropped;
            log.info("Mentions-Slice {}: {} geparst, {} gekoppelt behalten, {} verworfen",
                    stamp(slice), parsed.size(), kept.size(), dropped);
        } else {
            kept = parsed;
        }
        return store.insertMentions(kept);
    }

    /**
     * Phase 2: über die committeten Mentions dieses Slices die fehlenden Events ermitteln und
     * auflösen. Der aktuelle Slice wird höchstens einmal geladen; ältere Slices werden gebündelt
     * (jeder einmal, mit Retries) geholt. Nicht auffindbare Events werden als Stub angelegt.
     *
     * @return Anzahl geschriebener Events
     */
    private long resolveSliceEvents(LocalDateTime slice, Counters counters) {
        Instant sliceStart = slice.toInstant(ZoneOffset.UTC);
        Instant sliceEnd = sliceStart.plus(SLICE_MINUTES, ChronoUnit.MINUTES);
        List<MissingEventRef> missing = store.findMissingEventRefs(sliceStart, sliceEnd);
        if (missing.isEmpty()) {
            return 0;
        }
        // Fehlende Events nach ihrem Herkunfts-Slice (eventTimeDate) bündeln.
        Map<Instant, Set<Long>> neededBySlice = new HashMap<>();
        for (MissingEventRef ref : missing) {
            neededBySlice.computeIfAbsent(ref.eventTimeDate(), k -> new HashSet<>()).add(ref.globalEventId());
        }
        List<GdeltEvent> toWrite = new ArrayList<>();
        for (Map.Entry<Instant, Set<Long>> entry : neededBySlice.entrySet()) {
            Instant eventSlice = entry.getKey();
            Set<Long> neededIds = entry.getValue();
            boolean current = eventSlice.equals(sliceStart);
            if (!current) {
                counters.eventBackfillSlices++;
            }
            Map<Long, GdeltEvent> index = loadSliceEvents(eventSlice, current ? 1 : eventBackfillRetries, counters);
            for (Long id : neededIds) {
                GdeltEvent event = index.get(id);
                if (event != null) {
                    toWrite.add(event);
                    if (current) {
                        counters.eventsFromCurrentSlice++;
                    } else {
                        counters.eventsBackfilled++;
                    }
                } else {
                    toWrite.add(stubEvent(id, eventSlice)); // Slice gelesen, aber Event nicht enthalten
                    counters.eventsStubbed++;
                }
            }
        }
        int written = store.insertEvents(toWrite);
        log.info("Events-Slice {}: {} referenziert & fehlend -> {} geschrieben", stamp(slice), missing.size(), written);
        return written;
    }

    /** Fallback bei deaktivierter Kopplung: alle Events des Slices ungefiltert schreiben. */
    private long fetchWriteAllEvents(LocalDateTime slice, Counters counters) {
        Optional<List<String[]>> raw = client.download(GdeltDataset.EVENTS, slice);
        if (raw.isEmpty()) {
            counters.skippedSlices++;
            return 0;
        }
        return store.insertEvents(mapRows(raw.get(), eventMapper::map, counters));
    }

    /**
     * Lädt die Events eines Slices und indiziert sie nach {@code globalEventId}. Bis zu
     * {@code attempts} Leseversuche; danach leerer Index (Aufrufer legt Stubs an).
     */
    private Map<Long, GdeltEvent> loadSliceEvents(Instant eventSlice, int attempts, Counters counters) {
        LocalDateTime sliceLdt = LocalDateTime.ofInstant(eventSlice, ZoneOffset.UTC);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<List<String[]>> raw = client.download(GdeltDataset.EVENTS, sliceLdt);
            if (raw.isPresent()) {
                List<GdeltEvent> parsed = mapRows(raw.get(), eventMapper::map, counters);
                Map<Long, GdeltEvent> index = new HashMap<>(Math.max(16, parsed.size() * 2));
                for (GdeltEvent event : parsed) {
                    if (event.getGlobalEventId() != null) {
                        index.put(event.getGlobalEventId(), event);
                    }
                }
                return index;
            }
        }
        log.warn("Event-Slice {} nach {} Versuch(en) nicht lesbar -> Stubs für fehlende IDs",
                stamp(sliceLdt), attempts);
        return Map.of();
    }

    /**
     * Stub-Event für Stabilität: nur {@code global_event_id} + {@code date_added} (aus
     * {@code eventTimeDate}, passend zum DATEADDED-Slice). Verhindert wiederholtes Suchen; ein
     * Housekeeping-Job kann später das echte Event nachladen.
     */
    private static GdeltEvent stubEvent(Long globalEventId, Instant eventTimeDate) {
        GdeltEvent stub = new GdeltEvent();
        stub.setGlobalEventId(globalEventId);
        stub.setDateAdded(eventTimeDate);
        return stub;
    }

    /** Bildet Rohzeilen auf Entities ab; {@code null}-Ergebnisse und Parse-Fehler werden verworfen. */
    private <T> List<T> mapRows(List<String[]> rows, Function<String[], T> mapper, Counters counters) {
        List<T> out = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            try {
                T entity = mapper.apply(row);
                if (entity == null) {
                    counters.malformedRows++;
                } else {
                    out.add(entity);
                }
            } catch (RuntimeException e) {
                counters.malformedRows++;
                log.debug("Rohzeile verworfen (Parse-Fehler): {}", e.toString());
            }
        }
        return out;
    }

    /**
     * Loggt die aggregierte Themen-Statistik des Laufs: alle vorgekommenen GKG-Themen-Codes
     * absteigend nach Artikelzahl, mit Markierung, welche aktuell als marktrelevant gelten.
     */
    private void logThemeHistogram(LocalDate day, ThemeHistogram histogram) {
        StringBuilder sb = new StringBuilder()
                .append("GDELT-Themen-Statistik ").append(day).append(": ")
                .append(histogram.articles()).append(" GKG-Artikel geparst, ")
                .append(histogram.distinctCodes())
                .append(" verschiedene Themen-Codes")
                .append(" (Format: <Artikelzahl> <RELEVANT|-> <Code>, absteigend):");
        for (Map.Entry<String, Long> entry : histogram.sortedByCountDesc()) {
            String marker = marketRelevanceFilter.isRelevantCode(entry.getKey()) ? "RELEVANT" : "-";
            sb.append(System.lineSeparator())
                    .append(String.format("  %8d %-8s %s", entry.getValue(), marker, entry.getKey()));
        }
        log.info("{}", sb);
    }

    private static String stamp(LocalDateTime slice) {
        return slice.atOffset(ZoneOffset.UTC).format(STAMP);
    }

    /** Veränderlicher Zähler-Akkumulator über den gesamten Tageslauf. */
    private static final class Counters {
        private long skippedSlices;
        private long malformedRows;
        private long gkgFiltered;
        private long mentionsFiltered;
        private long eventsFromCurrentSlice;
        private long eventsBackfilled;
        private long eventsStubbed;
        private long eventBackfillSlices;
    }

    /** Ergebnis eines GKG-Slices: geschriebene Artikel + URLs der behaltenen (für Mentions-Kopplung). */
    private record SliceGkg(int written, Set<String> relevantUrls) {}
}
