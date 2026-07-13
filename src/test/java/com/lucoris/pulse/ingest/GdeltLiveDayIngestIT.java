package com.lucoris.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.ingest.gdelt.DayIngestReport;
import com.lucoris.pulse.ingest.gdelt.GdeltIngestService;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Live-Integrationstest: ruft den VOLLEN Vortag (ein Tag vor dem aktuellen UTC-Datum) ab 00:00
 * UTC chronologisch in allen 96 15-Minuten-Slices direkt und kostenlos von GDELT ab und schreibt
 * jeden Slice unmittelbar in die DB.
 *
 * <p><b>Manuell / aus der CI ausgeschlossen:</b> läuft nur bei gesetzter Umgebungsvariable
 * {@code GDELT_LIVE_IT=true}, damit {@code mvn verify} nicht bei jedem Build einen vollen Tag
 * (~1&nbsp;GB+, mehrere Minuten, netzabhängig) herunterlädt. On-Demand-Start:
 *
 * <pre>GDELT_LIVE_IT=true mvn -Dit.test=GdeltLiveDayIngestIT verify</pre>
 *
 * <p><b>Grenze:</b> {@code gdelt_mentions}/{@code gdelt_gkg} sind partitioniert; V1 kennt nur
 * 2026-07/2026-08. Der Test gelingt nur, wenn „Vortag" in diesen Bereich fällt.
 */
@ActiveProfiles("ingest")
@EnabledIfEnvironmentVariable(named = "GDELT_LIVE_IT", matches = "true")
class GdeltLiveDayIngestIT extends AbstractPostgresIT {

    @Autowired GdeltIngestService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    @AfterEach
    void truncate() {
        // Firehose-Commits liegen außerhalb von Springs Rollback -> explizit aufräumen.
        // CASCADE: event_significance hat einen FK auf gdelt_events. Partitionen werden mitgeleert.
        //jdbc.execute("TRUNCATE TABLE gdelt_events, gdelt_mentions, gdelt_gkg, ingest_log CASCADE");
    }

    @Test
    void fetchesFullPreviousDayChronologicallyIntoAllThreeRawTables() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);

        DayIngestReport report = service.ingestDay(yesterday);

        // Es müssen Roh-Daten in allen drei Tabellen gelandet sein.
        assertThat(count("gdelt_events")).isPositive();
        assertThat(count("gdelt_mentions")).isPositive();
        assertThat(count("gdelt_gkg")).isPositive();

        // Report-Summen stimmen mit den tatsächlich geschriebenen Zeilen überein.
        assertThat(report.events()).isEqualTo(count("gdelt_events"));
        assertThat(report.mentions()).isEqualTo(count("gdelt_mentions"));
        assertThat(report.gkg()).isEqualTo(count("gdelt_gkg"));
        assertThat(report.day()).isEqualTo(yesterday);
    }

    private long count(String table) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0L : n;
    }
}
