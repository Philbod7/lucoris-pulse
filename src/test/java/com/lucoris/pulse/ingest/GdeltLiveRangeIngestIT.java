package com.lucoris.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.ingest.gdelt.DayIngestReport;
import com.lucoris.pulse.ingest.gdelt.GdeltIngestService;
import com.lucoris.pulse.ingest.gdelt.RangeIngestReport;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Live-Integrationstest: ruft einen MANUELL eingestellten, halboffenen UTC-Datumsbereich
 * {@code [von, bis)} — {@code von} inklusive, {@code bis} exklusiv — tageweise direkt und
 * kostenlos von GDELT ab (jeder Tag in allen 96 15-Minuten-Slices) und schreibt jeden Slice
 * unmittelbar in die DB. Analog zu {@link GdeltLiveDayIngestIT}, nur über einen Bereich statt
 * einen einzelnen Tag.
 *
 * <p><b>Manuell einstellen:</b> {@link #RANGE_UTC} auf den gewünschten Bereich setzen, Format
 * {@code "von..bis"} (ISO-Datum, {@code bis} exklusiv). {@code bis} weglassen — also nur
 * {@code "2026-07-08"} oder {@code "2026-07-08.."} — liest genau den einen Tag {@code von}
 * vollständig ({@code bis = von + 1 Tag}).
 *
 * <p><b>Manuell / aus der CI ausgeschlossen:</b> läuft nur bei gesetzter Umgebungsvariable
 * {@code GDELT_LIVE_IT=true}, damit {@code mvn verify} nicht bei jedem Build mehrere volle Tage
 * (je ~1&nbsp;GB+, mehrere Minuten, netzabhängig) herunterlädt. On-Demand-Start:
 *
 * <pre>GDELT_LIVE_IT=true mvn -Dit.test=GdeltLiveRangeIngestIT verify</pre>
 *
 * <p><b>Grenze:</b> {@code gdelt_mentions}/{@code gdelt_gkg} sind partitioniert; V1 kennt nur
 * 2026-07/2026-08. Der Test gelingt nur, wenn der gesamte Bereich in diese Monate fällt (und
 * GDELT für die Tage bereits Daten bereitstellt).
 */
@ActiveProfiles("ingest")
@EnabledIfEnvironmentVariable(named = "GDELT_LIVE_IT", matches = "true")
class GdeltLiveRangeIngestIT extends AbstractPostgresIT {

    /**
     * Manuell einzustellender Bereich, Format {@code "von..bis"} ({@code bis} exklusiv). Leeres/
     * fehlendes {@code bis} liest genau den Tag {@code von}. Innerhalb 2026-07/2026-08 halten.
     */
    private static final String RANGE_UTC = "2026-07-05..2026-07-08";

    @Autowired GdeltIngestService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void truncate() {
        // Firehose-Commits liegen außerhalb von Springs Rollback -> explizit aufräumen.
        // CASCADE: event_significance hat einen FK auf gdelt_events. Partitionen werden mitgeleert.
        //jdbc.execute("TRUNCATE TABLE gdelt_events, gdelt_mentions, gdelt_gkg, ingest_log CASCADE");
    }

    @Test
    void fetchesFullDateRangeChronologicallyIntoAllThreeRawTables() {
        LocalDate von = parseVon(RANGE_UTC);
        LocalDate bis = parseBis(RANGE_UTC); // null => nur der eine Tag von

        RangeIngestReport report = service.ingestRange(von, bis);

        LocalDate effectiveBis = (bis == null) ? von.plusDays(1) : bis;
        List<LocalDate> expectedDays = von.datesUntil(effectiveBis).toList(); // [von, effectiveBis)

        // Bereichsgrenzen und Tages-Sequenz stimmen (halboffen, bis exklusiv).
        assertThat(report.vonInclusive()).isEqualTo(von);
        assertThat(report.bisExclusive()).isEqualTo(effectiveBis);
        assertThat(report.dayCount()).isEqualTo(expectedDays.size());
        assertThat(report.days()).extracting(DayIngestReport::day)
                .containsExactlyElementsOf(expectedDays);

        // Es müssen Roh-Daten in allen drei Tabellen gelandet sein.
        assertThat(count("gdelt_events")).isPositive();
        assertThat(count("gdelt_mentions")).isPositive();
        assertThat(count("gdelt_gkg")).isPositive();

        // Aggregat-Summen des Bereichs stimmen mit den tatsächlich geschriebenen Zeilen überein.
        assertThat(report.events()).isEqualTo(count("gdelt_events"));
        assertThat(report.mentions()).isEqualTo(count("gdelt_mentions"));
        assertThat(report.gkg()).isEqualTo(count("gdelt_gkg"));
    }

    /** Erstes Datumsfeld (vor {@code ".."}) des Bereichs-Strings. */
    private static LocalDate parseVon(String range) {
        return LocalDate.parse(range.split("\\.\\.", -1)[0].trim());
    }

    /** Zweites Datumsfeld (nach {@code ".."}) oder {@code null}, wenn keines angegeben ist. */
    private static LocalDate parseBis(String range) {
        String[] parts = range.split("\\.\\.", -1);
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        return LocalDate.parse(parts[1].trim());
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }
}
