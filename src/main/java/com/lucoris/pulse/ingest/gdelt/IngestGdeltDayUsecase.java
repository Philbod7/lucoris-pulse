package com.lucoris.pulse.ingest.gdelt;

import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import com.lucoris.pulse.core.domain.IngestLog;
import com.lucoris.pulse.core.domain.UrlIndex;
import java.time.Clock;
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
import java.util.Objects;
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
 *       {@link GdeltMention} werden zusammen mit ihren {@code ingest_log}-Vermerken in EINER
 *       Transaktion geschrieben. Ist der GKG-Slice laut {@code ingest_log} bereits eingelesen, wird
 *       Phase 1 übersprungen (verhindert doppeltes Speichern / Constraint-Verletzungen).</li>
 *   <li><b>Phase 2</b> — über die committeten Mentions dieses Slices werden per SQL die fehlenden
 *       Events ermittelt und aus ihren {@code eventTimeDate}-Slices aufgelöst (aktueller Slice nur
 *       einmal geladen; ältere gebündelt mit Retries; nicht Auffindbares als Stub). Idempotent über
 *       {@code not exists}.</li>
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
    /** URL-Index-Flag: primäre Quelle (Event.source_url). */
    private static final String SOURCE_PRIMARY = "P";
    /** URL-Index-Flag: sekundäre Quelle (Mention.mention_identifier). */
    private static final String SOURCE_SECONDARY = "S";

    private final GdeltSliceClient client;
    private final FirehoseStore store;
    private final GdeltEventRowMapper eventMapper;
    private final GdeltMentionRowMapper mentionMapper;
    private final GdeltGkgRowMapper gkgMapper;
    private final MarketRelevanceFilter marketRelevanceFilter;
    private final boolean logThemeHistogram;
    private final boolean filterLinkedEventsAndMentions;
    private final int eventBackfillRetries;
    private final Clock clock;

    public IngestGdeltDayUsecase(
            GdeltSliceClient client,
            FirehoseStore store,
            GdeltEventRowMapper eventMapper,
            GdeltMentionRowMapper mentionMapper,
            GdeltGkgRowMapper gkgMapper,
            MarketRelevanceFilter marketRelevanceFilter,
            boolean logThemeHistogram,
            boolean filterLinkedEventsAndMentions,
            int eventBackfillRetries,
            Clock clock) {
        this.client = client;
        this.store = store;
        this.eventMapper = eventMapper;
        this.mentionMapper = mentionMapper;
        this.gkgMapper = gkgMapper;
        this.marketRelevanceFilter = marketRelevanceFilter;
        this.logThemeHistogram = logThemeHistogram;
        this.filterLinkedEventsAndMentions = filterLinkedEventsAndMentions;
        this.eventBackfillRetries = Math.max(1, eventBackfillRetries);
        this.clock = clock;
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
        Instant now = Instant.now(clock);               // Cutoff: nichts in der Zukunft abrufen
        Counters counters = new Counters();
        ThemeHistogram histogram = new ThemeHistogram();
        long events = 0;
        long mentions = 0;
        long gkg = 0;

        for (int i = 0; i < SLICES_PER_DAY; i++) {
            LocalDateTime slice = dayStart.plusMinutes((long) SLICE_MINUTES * i); // 00:00 .. 23:45
            if (slice.toInstant(ZoneOffset.UTC).isAfter(now)) {
                // Slice liegt in der Zukunft — Abruf stoppen (Slices sind chronologisch aufsteigend).
                log.info("Aktuelle Zeit erreicht — Abbruch bei Slice {}: keine Zukunfts-Slices abrufen",
                        stamp(slice));
                break;
            }
            SlicePhase1 phase1 = processPhase1(slice, counters, histogram);
            gkg += phase1.gkg();
            mentions += phase1.mentions();
            if (filterLinkedEventsAndMentions) {
                events += resolveSliceEvents(slice, counters);
            } else {
                events += fetchWriteAllEvents(slice, counters);
            }
        }

        DayIngestReport report = new DayIngestReport(
                dayUtc, events, mentions, gkg,
                counters.eventsBackfilled, counters.eventsStubbed, counters.eventBackfillSlices,
                counters.mentionsFiltered, counters.gkgFiltered, counters.slicesAlreadyProcessed,
                counters.skippedSlices, counters.malformedRows);
        log.info(
                "GDELT-Tagesabruf {} abgeschlossen: events={} (ausSlice={}, backfill={}, stubs={}), "
                        + "mentions={}, gkg(marktrelevant)={}, verworfen mentions={}/gkg={}, "
                        + "bereitsEingelesen={}, ältereSlices={}, übersprungeneSlices={}, verworfeneZeilen={}",
                dayUtc, events, counters.eventsFromCurrentSlice, counters.eventsBackfilled,
                counters.eventsStubbed, mentions, gkg, counters.mentionsFiltered, counters.gkgFiltered,
                counters.slicesAlreadyProcessed, counters.eventBackfillSlices, counters.skippedSlices,
                counters.malformedRows);
        if (logThemeHistogram) {
            logThemeHistogram(dayUtc, histogram);
        }
        return report;
    }

    /**
     * Liest den halboffenen UTC-Datumsbereich {@code [von, bis)} tageweise ein — {@code von}
     * inklusive, {@code bis} exklusive (Iteration mit {@code <}). Jeder Tag wird über
     * {@link #ingestDay(LocalDate)} vollständig und idempotent verarbeitet.
     *
     * @param vonUtcInclusive erster einzulesender Tag (inklusive, UTC-Kalendertag); nicht {@code null}
     * @param bisUtcExclusive exklusive Obergrenze (UTC-Kalendertag); {@code null} liest genau den
     *                        einen Tag {@code von} vollständig ({@code bis = von + 1 Tag})
     * @return Aggregat mit Tages-Reports und Gesamtsummen über den Bereich
     * @throws NullPointerException     wenn {@code von} {@code null} ist
     * @throws IllegalArgumentException wenn {@code bis} nicht nach {@code von} liegt
     */
    public RangeIngestReport ingestRange(LocalDate vonUtcInclusive, LocalDate bisUtcExclusive) {
        Objects.requireNonNull(vonUtcInclusive, "von darf nicht null sein");
        LocalDate bis = (bisUtcExclusive == null) ? vonUtcInclusive.plusDays(1) : bisUtcExclusive;
        if (!bis.isAfter(vonUtcInclusive)) {
            throw new IllegalArgumentException(
                    "bis muss nach von liegen (exklusive Obergrenze): von="
                            + vonUtcInclusive + ", bis=" + bis);
        }
        List<DayIngestReport> days = new ArrayList<>();
        for (LocalDate d = vonUtcInclusive; d.isBefore(bis); d = d.plusDays(1)) {
            days.add(ingestDay(d));
        }
        RangeIngestReport report = new RangeIngestReport(vonUtcInclusive, bis, days);
        log.info(
                "GDELT-Bereichsabruf {}..{} (exkl.) abgeschlossen: {} Tag(e), events={}, mentions={}, gkg={}",
                vonUtcInclusive, bis, report.dayCount(), report.events(), report.mentions(), report.gkg());
        return report;
    }

    /**
     * Phase 1: relevante GKG + gekoppelte Mentions eines Slices zusammen mit ihren
     * {@code ingest_log}-Vermerken atomar schreiben. Bereits eingelesene Slices (laut
     * {@code ingest_log}) werden übersprungen. Ein fehlender GKG-Slice (404) wird NICHT vermerkt,
     * damit ein späterer Lauf ihn erneut versuchen kann.
     */
    private SlicePhase1 processPhase1(LocalDateTime slice, Counters counters, ThemeHistogram histogram) {
        String stamp = stamp(slice);
        String gkgFile = GdeltDataset.GKG.filename(stamp);
        if (store.isFileProcessed(gkgFile)) {
            counters.slicesAlreadyProcessed++;
            log.info("Slice {} ausgelassen — bereits eingelesen (ingest_log {})", stamp, gkgFile);
            return SlicePhase1.SKIPPED;
        }
        Optional<List<String[]>> gkgRaw = client.download(GdeltDataset.GKG, slice);
        if (gkgRaw.isEmpty()) {
            counters.skippedSlices++;
            log.info("GKG-Datei {} fehlt/nicht abrufbar — Slice ausgelassen", gkgFile);
            return SlicePhase1.SKIPPED;
        }
        List<GdeltGkg> parsedGkg = mapRows(gkgRaw.get(), gkgMapper::map, counters);
        List<GdeltGkg> relevantGkg = new ArrayList<>(parsedGkg.size());
        Set<String> relevantUrls = new HashSet<>();
        for (GdeltGkg article : parsedGkg) {
            histogram.addArticle(GdeltThemes.codes(article.getV2Themes())); // Statistik über ALLE Artikel
            if (marketRelevanceFilter.isRelevant(article)) {
                relevantGkg.add(article);
                if (article.getDocumentIdentifier() != null) {
                    relevantUrls.add(article.getDocumentIdentifier());
                }
            }
        }
        counters.gkgFiltered += parsedGkg.size() - relevantGkg.size();

        List<GdeltMention> keptMentions = new ArrayList<>();
        Optional<List<String[]>> mentionsRaw = client.download(GdeltDataset.MENTIONS, slice);
        if (mentionsRaw.isPresent()) {
            List<GdeltMention> parsedMentions = mapRows(mentionsRaw.get(), mentionMapper::map, counters);
            if (filterLinkedEventsAndMentions) {
                for (GdeltMention mention : parsedMentions) {
                    if (relevantUrls.contains(mention.getMentionIdentifier())) {
                        keptMentions.add(mention);
                    }
                }
                counters.mentionsFiltered += parsedMentions.size() - keptMentions.size();
            } else {
                keptMentions.addAll(parsedMentions);
            }
        } else {
            counters.skippedSlices++;
            log.info("Mentions-Datei {} fehlt/nicht abrufbar — ausgelassen",
                    GdeltDataset.MENTIONS.filename(stamp));
        }

        // Atomar: GKG + Mentions + url_index(S) + ingest_log(gkg) + ingest_log(mentions) in EINER Tx.
        List<Object> batch = new ArrayList<>(relevantGkg.size() + keptMentions.size() * 2 + 2);
        batch.addAll(relevantGkg);
        batch.addAll(keptMentions);
        // Sekundär-Zeilen des URL-Index: Mentions tragen URL UND global_event_id (GKG hätte keine).
        for (GdeltMention mention : keptMentions) {
            if (mention.getMentionIdentifier() != null && mention.getGlobalEventId() != null) {
                batch.add(urlIndexRow(mention.getGlobalEventId(), mention.getMentionIdentifier(), SOURCE_SECONDARY));
            }
        }
        batch.add(ingestLogEntry(gkgFile, GdeltDataset.GKG, relevantGkg.size()));
        batch.add(ingestLogEntry(GdeltDataset.MENTIONS.filename(stamp), GdeltDataset.MENTIONS, keptMentions.size()));
        store.insertAtomic(batch);

        log.info("Slice {} Phase 1 geschrieben: gkg(marktrelevant)={}, mentions(gekoppelt)={}",
                stamp, relevantGkg.size(), keptMentions.size());
        return new SlicePhase1(relevantGkg.size(), keptMentions.size());
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
        // Atomar: Events + Primär-Zeilen des URL-Index (Event.source_url) in EINER Transaktion.
        List<Object> batch = new ArrayList<>(toWrite.size() * 2);
        batch.addAll(toWrite);
        addPrimaryUrlIndexRows(batch, toWrite);
        store.insertAtomic(batch);
        int written = toWrite.size(); // nur Events zählen, NICHT die url_index-Zeilen
        log.info("Events-Slice {}: {} referenziert & fehlend -> {} geschrieben", stamp(slice), missing.size(), written);
        return written;
    }

    /**
     * Fallback bei deaktivierter Kopplung: alle Events des Slices ungefiltert schreiben — mit
     * {@code ingest_log}-Vermerk in derselben Transaktion und Skip bereits eingelesener Slices.
     */
    private long fetchWriteAllEvents(LocalDateTime slice, Counters counters) {
        String eventsFile = GdeltDataset.EVENTS.filename(stamp(slice));
        if (store.isFileProcessed(eventsFile)) {
            counters.slicesAlreadyProcessed++;
            log.info("Events-Datei {} ausgelassen — bereits eingelesen (ingest_log)", eventsFile);
            return 0;
        }
        Optional<List<String[]>> raw = client.download(GdeltDataset.EVENTS, slice);
        if (raw.isEmpty()) {
            counters.skippedSlices++;
            log.info("Events-Datei {} fehlt/nicht abrufbar — ausgelassen", eventsFile);
            return 0;
        }
        List<GdeltEvent> parsed = mapRows(raw.get(), eventMapper::map, counters);
        List<Object> batch = new ArrayList<>(parsed.size() * 2 + 1);
        batch.addAll(parsed);
        addPrimaryUrlIndexRows(batch, parsed); // Primär-Zeilen des URL-Index (Event.source_url)
        batch.add(ingestLogEntry(eventsFile, GdeltDataset.EVENTS, parsed.size()));
        store.insertAtomic(batch);
        return parsed.size();
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

    /**
     * Hängt Primär-Zeilen des URL-Index an den Batch: je Event mit gesetzter {@code source_url}
     * eine {@code 'P'}-Zeile. Stubs haben {@code null} source_url und werden übersprungen.
     */
    private static void addPrimaryUrlIndexRows(List<Object> batch, List<GdeltEvent> events) {
        for (GdeltEvent event : events) {
            if (event.getSourceUrl() != null && event.getGlobalEventId() != null) {
                batch.add(urlIndexRow(event.getGlobalEventId(), event.getSourceUrl(), SOURCE_PRIMARY));
            }
        }
    }

    /** Eine URL-Index-Zeile (append-only, ohne PK — Dubletten sind erlaubt). */
    private static UrlIndex urlIndexRow(Long globalEventId, String url, String sourceFlag) {
        return new UrlIndex(globalEventId, url, sourceFlag);
    }

    /** {@code ingest_log}-Eintrag; {@code processed_at} setzt die DB (Default {@code now()}). */
    private static IngestLog ingestLogEntry(String filename, GdeltDataset dataset, int rowCount) {
        IngestLog entry = new IngestLog();
        entry.setFilename(filename);
        entry.setDataset(dataset.logName());
        entry.setRowCount(rowCount);
        return entry;
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
        private long slicesAlreadyProcessed;
    }

    /** Ergebnis der Phase 1 eines Slices: geschriebene GKG-Artikel und Mentions. */
    private record SlicePhase1(int gkg, int mentions) {
        private static final SlicePhase1 SKIPPED = new SlicePhase1(0, 0);
    }
}
