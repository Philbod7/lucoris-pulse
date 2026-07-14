package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.ingest.config.PrimarySourceProperties;
import com.lucoris.pulse.ingest.primary.adapter.HttpFeedFetcher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live-Integrationstest gegen die ECHTEN Feeds von ECB und Fed.
 *
 * <p><b>Manuell / vom Standard-Build ausgeschlossen:</b> läuft nur bei gesetzter Umgebungsvariable
 * {@code PRIMARY_LIVE_IT=true} — genau wie {@code GdeltLiveDayIngestIT}. Ohne die Variable
 * deaktiviert JUnit den Test, BEVOR auch nur ein Socket geöffnet wird; {@code mvn verify} bleibt
 * damit offline. On-Demand-Start:
 *
 * <pre>PRIMARY_LIVE_IT=true mvn -Dit.test=PrimaryRssLiveIT verify</pre>
 *
 * <p>Braucht weder Spring noch Datenbank — der Ingest-Pfad wird von Hand zusammengesteckt. Erbt
 * deshalb bewusst NICHT von {@code AbstractPostgresIT}.
 *
 * <p><b>Grenze:</b> {@code federalreserve.gov} weist Bot-User-Agents zeitweise mit HTTP 403 ab. Der
 * Fetcher liefert dann {@code Optional.empty()} und der Test schlägt fehl — das ist dann eine echte
 * Aussage über die Erreichbarkeit, kein Fehler im Code.
 */
@EnabledIfEnvironmentVariable(named = "PRIMARY_LIVE_IT", matches = "true")
class PrimaryRssLiveIT {

    private final PrimarySourceManifestLoader registry = new PrimarySourceManifestLoader(
            JsonMapper.builder().build(), "primary-sources/lucoris-pulse-primary-sources.json");

    private final IngestPrimarySourcesUsecase usecase = usecase();

    private IngestPrimarySourcesUsecase usecase() {
        PrimarySourceProperties props = new PrimarySourceProperties();
        props.setConnectTimeout(Duration.ofSeconds(10));
        props.setRequestTimeout(Duration.ofSeconds(30));
        GenericRssAdapter rss = new GenericRssAdapter(new HttpFeedFetcher(props), Clock.systemUTC());
        return new IngestPrimarySourcesUsecase(
                registry, new AdapterDispatcher(Map.of(GenericRssAdapter.HANDLER, rss)));
    }

    @Test
    void bothEnabledFeedsDeliverUsableEventsRightNow() {
        List<PrimaryEvent> events = usecase.run();

        assertThat(events).isNotEmpty();

        // Beide aktivierten Quellen müssen etwas geliefert haben.
        assertThat(events).extracting(PrimaryEvent::sourceId)
                .contains("ecb-press", "fed-monetary");

        // Jedes Ereignis ist brauchbar: Deep-Link, plausibles Datum, Rechtslage der Quelle.
        Instant jetzt = Instant.now();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.url()).startsWith("http");
            assertThat(event.title()).isNotBlank();
            assertThat(event.publishedAt()).isNotNull();
            assertThat(event.publishedAt()).isBefore(jetzt.plusSeconds(3600)); // nicht aus der Zukunft
            assertThat(event.publishedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
            assertThat(event.legalClass()).isEqualTo("A");
            assertThat(event.fetchedAt()).isNotNull();
        });

        // Die ECB-Attributionsformel muss am Ereignis hängen — das Rendering baut daraus die Quellzeile.
        assertThat(events)
                .filteredOn(event -> "ecb-press".equals(event.sourceId()))
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.attribution()).isNotNull();
                    assertThat(event.attribution().required()).isTrue();
                    assertThat(event.attribution().formula()).contains("Europaeische Zentralbank");
                });
    }
}
