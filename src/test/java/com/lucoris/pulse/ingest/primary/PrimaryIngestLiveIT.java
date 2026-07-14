package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Live-Integrationstest des VOLLEN Persistenz-Pfads gegen die ECHTEN Feeds aller aktivierten
 * Quellen: fetch -> robots/TDM-Gate -> parse -> dedup -> PostgreSQL (Testcontainers). Läuft
 * ZWEIMAL und beweist damit die Idempotenz: der zweite Lauf dedupliziert (nahezu) alles.
 *
 * <p>Anders als {@code PrimaryRssLiveIT} (fetch-only, ohne Spring/DB) braucht dieser Test die
 * Datenbank und den Spring-Kontext — daher {@code AbstractPostgresIT} + Profil {@code ingest}.
 * Der Poller bleibt aus (Property-Default {@code poll.enabled=false}); abgerufen wird explizit
 * über {@code runAll()}.
 *
 * <p><b>Manuell / vom Standard-Build ausgeschlossen:</b> läuft nur bei gesetzter Umgebungsvariable
 * {@code PRIMARY_LIVE_IT=true}. On-Demand-Start:
 *
 * <pre>PRIMARY_LIVE_IT=true mvn -Dit.test=PrimaryIngestLiveIT verify</pre>
 *
 * <p>Die Summary je Quelle (gefetcht / neu / dedupliziert / Fehler) steht im Log — für beide
 * Läufe.
 */
@ActiveProfiles("ingest")
@EnabledIfEnvironmentVariable(named = "PRIMARY_LIVE_IT", matches = "true")
class PrimaryIngestLiveIT extends AbstractPostgresIT {

    private static final Logger log = LoggerFactory.getLogger(PrimaryIngestLiveIT.class);

    @Autowired IngestPrimarySourcesUsecase usecase;
    @Autowired PrimarySourceManifestLoader registry;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void truncate() {
        // StatelessSession committet außerhalb von Springs Rollback.
        // jdbc.execute("TRUNCATE TABLE primary_feed_item, primary_source_state");
    }

    @Test
    void bothRunsDeliverAndTheSecondDeduplicatesEverything() {
        PrimaryIngestReport erster = usecase.runAll();
        logSummary("Lauf 1", erster);

        PrimaryIngestReport zweiter = usecase.runAll();
        logSummary("Lauf 2", zweiter);

        // Jede aktivierte Quelle hat in beiden Läufen ein Ergebnis (auch destatis-press:
        // mode=calendar betrifft nur den Poller, nicht den expliziten Einmal-Abruf).
        var enabledIds = registry.enabledSources().stream()
                .map(IngestSource::id)
                .collect(Collectors.toList());
        assertThat(erster.results()).extracting(SourceRunResult::sourceId)
                .containsExactlyElementsOf(enabledIds);
        assertThat(zweiter.results()).extracting(SourceRunResult::sourceId)
                .containsExactlyElementsOf(enabledIds);

        // Der erste Lauf muss substanziell liefern; einzelne Quellen dürfen (temporär) scheitern,
        // aber nicht alle.
        assertThat(erster.totalNew()).isPositive();
        assertThat(erster.failures().size()).isLessThan(erster.results().size());

        // Idempotenz: was der zweite Lauf sieht, ist (bis auf zwischenzeitlich publizierte
        // Meldungen) schon gespeichert. Erfolgreiche Quellen liefern fast nur Dubletten.
        assertThat(zweiter.totalDeduped())
                .as("zweiter Lauf muss ganz überwiegend deduplizieren")
                .isGreaterThanOrEqualTo(zweiter.totalFetched() - zweiter.totalNew());
        zweiter.results().stream().filter(r -> !r.failed()).forEach(r ->
                assertThat(r.newItems())
                        .as("Quelle %s: zweiter Lauf darf höchstens Nachzügler neu speichern", r.sourceId())
                        .isLessThanOrEqualTo(Math.max(2, r.fetched() / 5)));

        // Die DB-Zeilen decken sich mit der Buchführung.
        Integer zeilen = jdbc.queryForObject("SELECT count(*) FROM primary_feed_item", Integer.class);
        assertThat(zeilen).isEqualTo(erster.totalNew() + zweiter.totalNew());
        Integer zustaende = jdbc.queryForObject("SELECT count(*) FROM primary_source_state", Integer.class);
        assertThat(zustaende).isEqualTo(enabledIds.size());
    }

    private static void logSummary(String label, PrimaryIngestReport report) {
        log.info("==== {} — Summary je Quelle ====", label);
        log.info(String.format("%-28s %9s %6s %13s   %s", "Quelle", "gefetcht", "neu", "dedupliziert", "Fehler"));
        for (SourceRunResult r : report.results()) {
            log.info(String.format("%-28s %9d %6d %13d   %s",
                    r.sourceId(), r.fetched(), r.newItems(), r.deduped(),
                    r.error() == null ? "-" : r.error()));
        }
        log.info(String.format("%-28s %9d %6d %13d   %d Fehler", "SUMME",
                report.totalFetched(), report.totalNew(), report.totalDeduped(),
                report.failures().size()));
    }
}
