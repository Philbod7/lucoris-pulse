package com.lucoris.pulse.ingest.gdelt;

import com.lucoris.pulse.core.domain.GdeltGkg;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Geschäftslogik des GDELT-Tagesabrufs (reines POJO, ohne Spring). Iteriert einen UTC-Tag
 * chronologisch in 96 15-Minuten-Slices (00:00 bis 23:45) und schreibt für jeden Slice die drei
 * Roh-Datensätze (Events, Mentions, GKG) DIREKT nach dem jeweiligen Abruf über den
 * {@link FirehoseStore} weg — nichts wird bis zum Tagesende gepuffert.
 *
 * <p>Fehlende Slices (404) werden übersprungen; strukturell ungültige oder nicht parsebare
 * Rohzeilen werden verworfen und gezählt, ohne den Tageslauf abzubrechen.
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

    public IngestGdeltDayUsecase(
            GdeltSliceClient client,
            FirehoseStore store,
            GdeltEventRowMapper eventMapper,
            GdeltMentionRowMapper mentionMapper,
            GdeltGkgRowMapper gkgMapper,
            MarketRelevanceFilter marketRelevanceFilter,
            boolean logThemeHistogram) {
        this.client = client;
        this.store = store;
        this.eventMapper = eventMapper;
        this.mentionMapper = mentionMapper;
        this.gkgMapper = gkgMapper;
        this.marketRelevanceFilter = marketRelevanceFilter;
        this.logThemeHistogram = logThemeHistogram;
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
            events += fetchMapWrite(
                    GdeltDataset.EVENTS, slice, eventMapper::map, store::insertEvents, counters);
            mentions += fetchMapWrite(
                    GdeltDataset.MENTIONS, slice, mentionMapper::map, store::insertMentions, counters);
            gkg += fetchFilterWriteGkg(slice, counters, histogram);
        }

        DayIngestReport report = new DayIngestReport(
                dayUtc, events, mentions, gkg, counters.gkgFiltered, counters.skippedSlices, counters.malformedRows);
        log.info(
                "GDELT-Tagesabruf {} abgeschlossen: events={}, mentions={}, gkg(marktrelevant)={}, "
                        + "gkgVerworfen(Filter)={}, übersprungeneSlices={}, verworfeneZeilen={}",
                dayUtc, events, mentions, gkg, counters.gkgFiltered, counters.skippedSlices, counters.malformedRows);
        if (logThemeHistogram) {
            logThemeHistogram(dayUtc, histogram);
        }
        return report;
    }

    /**
     * GKG-Slice: abrufen -> mappen -> Marktrelevanz-Filter (VOR dem Speichern) -> nur relevante
     * Artikel schreiben. Loggt je File eine Statistik (geparst / marktrelevant / verworfen).
     *
     * @return Anzahl geschriebener (marktrelevanter) GKG-Artikel des Slices
     */
    private int fetchFilterWriteGkg(LocalDateTime slice, Counters counters, ThemeHistogram histogram) {
        Optional<List<String[]>> raw = client.download(GdeltDataset.GKG, slice);
        if (raw.isEmpty()) {
            counters.skippedSlices++;
            return 0;
        }
        List<GdeltGkg> parsed = mapRows(raw.get(), gkgMapper::map, counters);
        List<GdeltGkg> relevant = new ArrayList<>(parsed.size());
        for (GdeltGkg gkg : parsed) {
            histogram.addArticle(GdeltThemes.codes(gkg.getV2Themes())); // Statistik über ALLE Artikel
            if (marketRelevanceFilter.isRelevant(gkg)) {
                relevant.add(gkg);
            }
        }
        int dropped = parsed.size() - relevant.size();
        counters.gkgFiltered += dropped;
        log.info(
                "GKG-Slice {}: {} Artikel geparst, {} marktrelevant behalten, {} verworfen",
                slice.atOffset(ZoneOffset.UTC).format(STAMP), parsed.size(), relevant.size(), dropped);
        return store.insertGkg(relevant);
    }

    /** Ein Datensatz eines Slices: abrufen -> mappen -> sofort schreiben. */
    private <T> int fetchMapWrite(
            GdeltDataset dataset,
            LocalDateTime slice,
            Function<String[], T> mapper,
            ToIntFunction<List<T>> writer,
            Counters counters) {
        Optional<List<String[]>> raw = client.download(dataset, slice);
        if (raw.isEmpty()) {
            counters.skippedSlices++;
            return 0;
        }
        List<T> mapped = mapRows(raw.get(), mapper, counters);
        return writer.applyAsInt(mapped);
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
     * Diagnose, um das Marktrelevanz-Set zu kuratieren.
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

    /** Veränderlicher Zähler-Akkumulator über den gesamten Tageslauf. */
    private static final class Counters {
        private long skippedSlices;
        private long malformedRows;
        private long gkgFiltered;
    }
}
