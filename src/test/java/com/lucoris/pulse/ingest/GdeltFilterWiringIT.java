package com.lucoris.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import com.lucoris.pulse.ingest.gdelt.DayIngestReport;
import com.lucoris.pulse.ingest.gdelt.FirehoseStore;
import com.lucoris.pulse.ingest.gdelt.GdeltDataset;
import com.lucoris.pulse.core.domain.IngestLog;
import com.lucoris.pulse.ingest.gdelt.GdeltIngestService;
import com.lucoris.pulse.ingest.gdelt.GdeltSliceClient;
import com.lucoris.pulse.ingest.gdelt.MissingEventRef;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Beweist, dass {@code lucoris.ingest.gdelt.market-relevant-theme-prefixes} aus der Konfiguration
 * tatsächlich das Filtern steuert — nicht nur der (zufällig identische) Default. Dazu wird das Set
 * per {@link TestPropertySource} auf ein NICHT-Default-Präfix ({@code TESTREL_}) gesetzt und ein
 * GKG-Slice mit einem passenden und einem {@code ECON_}-Artikel eingespeist. Erwartung: nur der
 * {@code TESTREL_}-Artikel wird geschrieben, der {@code ECON_}-Artikel (der nur den Default träfe)
 * wird verworfen. HTTP-Client und Firehose-Store sind durch Test-Doubles ersetzt (kein Netz,
 * keine DB-Schreibvorgänge).
 */
@ActiveProfiles("ingest")
@TestPropertySource(properties = {
        "lucoris.ingest.gdelt.market-relevant-theme-prefixes=TESTREL_",
        "lucoris.ingest.gdelt.log-theme-histogram=false"
})
@Import(GdeltFilterWiringIT.Stubs.class)
class GdeltFilterWiringIT extends AbstractPostgresIT {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);

    @Autowired GdeltIngestService service;
    @Autowired CapturingStore store;

    @Test
    void onlyArticlesMatchingConfiguredPrefixAreWritten() {
        DayIngestReport report = service.ingestDay(DAY);

        // Konfiguriert ist NUR "TESTREL_": der TESTREL_-Artikel bleibt, der ECON_-Artikel (nur
        // vom Default getroffen) wird verworfen -> beweist, dass der Config-Wert filtert.
        assertThat(store.gkgRows).extracting(GdeltGkg::getGkgRecordId).containsExactly("REL-1");
        assertThat(report.gkg()).isEqualTo(1);
        assertThat(report.gkgFiltered()).isEqualTo(1);
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        GdeltSliceClient stubClient() {
            return new StubClient();
        }

        @Bean
        @Primary
        CapturingStore capturingStore() {
            return new CapturingStore();
        }
    }

    /** Liefert für den ersten GKG-Slice des Tages zwei Artikel (relevant + irrelevant), sonst nichts. */
    static class StubClient implements GdeltSliceClient {
        @Override
        public Optional<List<String[]>> download(GdeltDataset dataset, LocalDateTime sliceStartUtc) {
            if (dataset == GdeltDataset.GKG && sliceStartUtc.equals(DAY.atStartOfDay())) {
                return Optional.of(List.of(
                        gkgRow("REL-1", "TESTREL_MARKET,10"),    // trifft konfiguriertes Präfix
                        gkgRow("ECON-2", "ECON_STOCKMARKET,10")  // träfe nur den Default -> muss raus
                ));
            }
            return Optional.empty();
        }

        private static String[] gkgRow(String id, String themes) {
            String[] c = new String[27];
            Arrays.fill(c, "");
            c[0] = id;
            c[1] = "20260710000000";
            c[3] = "example.com";
            c[4] = "https://example.com/" + id;
            c[8] = themes;
            return c;
        }
    }

    /** Fängt die geschriebenen GKG-Zeilen ab, statt in die DB zu schreiben. */
    static class CapturingStore implements FirehoseStore {
        final List<GdeltGkg> gkgRows = new ArrayList<>();
        final Set<String> processedFiles = new HashSet<>();

        @Override
        public int insertEvents(List<GdeltEvent> rows) {
            return rows.size();
        }

        @Override
        public int insertMentions(List<GdeltMention> rows) {
            return rows.size();
        }

        @Override
        public int insertGkg(List<GdeltGkg> rows) {
            gkgRows.addAll(rows);
            return rows.size();
        }

        @Override
        public int insertAtomic(List<?> rows) {
            for (Object row : rows) {
                if (row instanceof GdeltGkg g) {
                    gkgRows.add(g);
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
            return List.of(); // keine Mentions eingespeist -> nichts fehlt
        }
    }
}
