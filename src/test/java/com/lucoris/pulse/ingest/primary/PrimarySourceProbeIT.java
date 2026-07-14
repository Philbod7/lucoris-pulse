package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.ingest.config.PrimarySourceProperties;
import com.lucoris.pulse.ingest.primary.adapter.HttpFeedFetcher;
import com.lucoris.pulse.ingest.primary.adapter.HttpPolicyFetcher;
import com.lucoris.pulse.ingest.primary.robots.CachingRobotsGate;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tippt EINE benannte Quelle aus dem Produktions-Manifest an — auch eine noch nicht aktivierte.
 *
 * <p>Das Werkzeug für {@code confidence: verify_endpoint}: das Manifest verlangt „beim Einbau einmal
 * antippen und fixieren", und genau das passiert hier über den ECHTEN Ingest-Pfad (HTTP → Rome →
 * {@link PrimaryEvent}), nicht über einen zweiten, womöglich abweichenden Parser. Anders als
 * {@link PrimaryRssLiveIT} und {@code SourceLoadValidator} liest diese Klasse bewusst
 * {@code load().ingestSources()} statt {@code enabledSources()} — eine Quelle nur zum Antippen
 * scharf zu schalten wäre falsch herum.
 *
 * <p><b>Manuell / vom Standard-Build ausgeschlossen</b>, doppelt gegatet: die Umgebungsvariable
 * {@code PRIMARY_LIVE_IT=true} (wie bei den GDELT-Live-ITs) UND die Quell-ID als System-Property.
 * Fehlt eines von beiden, deaktiviert JUnit den Test, BEVOR ein Socket aufgeht — {@code mvn verify}
 * bleibt offline.
 *
 * <pre>PRIMARY_LIVE_IT=true mvn -Dit.test=PrimarySourceProbeIT -Dprimary.source=bmf-aktuelles verify</pre>
 *
 * <p>Braucht weder Spring noch Datenbank — der Ingest-Pfad wird von Hand zusammengesteckt. Erbt
 * deshalb bewusst NICHT von {@code AbstractPostgresIT}.
 *
 * <p><b>Grenze:</b> Ein Herausgeber, der Bots abweist (403, oder ein Redirect auf eine
 * Bot-Prüfseite), liefert keine Einträge und macht den Test rot. Das ist dann eine echte Aussage
 * über die Erreichbarkeit mit unserem User-Agent, kein Fehler im Code — und kein Grund, den
 * User-Agent zu fälschen.
 */
@EnabledIfEnvironmentVariable(named = "PRIMARY_LIVE_IT", matches = "true")
@EnabledIfSystemProperty(named = PrimarySourceProbeIT.SOURCE_PROPERTY, matches = ".+")
class PrimarySourceProbeIT {

    /** Quell-ID aus dem Manifest, z.B. {@code -Dprimary.source=bmf-aktuelles}. */
    static final String SOURCE_PROPERTY = "primary.source";

    private static final Logger log = LoggerFactory.getLogger(PrimarySourceProbeIT.class);

    private final PrimarySourceManifestLoader registry = new PrimarySourceManifestLoader(
            JsonMapper.builder().build(), new PrimarySourceProperties().getManifest());

    private final SourceAdapter dispatcher = dispatcher();

    private SourceAdapter dispatcher() {
        PrimarySourceProperties props = new PrimarySourceProperties();
        props.setConnectTimeout(Duration.ofSeconds(10));
        props.setRequestTimeout(Duration.ofSeconds(30));
        GenericRssAdapter rss = new GenericRssAdapter(new HttpFeedFetcher(props), Clock.systemUTC());
        AdapterDispatcher dispatcher = new AdapterDispatcher(Map.of(GenericRssAdapter.HANDLER, rss));

        // Auch die Probe läuft durch das Gate — gerade sie. Hier wird eine noch UNGEPRÜFTE Quelle
        // zum ersten Mal angefasst; das ist der Moment, in dem robots.txt/TDM zählen. Untersagt der
        // Herausgeber den Zugriff, wirft der Abruf (SourceNotPermittedException) und die Probe wird
        // rot — das ist dann das Ergebnis der Probe, kein Fehler im Test. Die robots-Prüfung, die
        // bisher von Hand in die notes wanderte, macht damit die Maschine.
        RobotsGate gate = new CachingRobotsGate(
                new HttpPolicyFetcher(props), JsonMapper.builder().build(), props.getUserAgent(),
                props.getRobotsSuccessTtl(), props.getRobotsFailureTtl(), props.getRobotsCacheMaxHosts(),
                Clock.systemUTC(), props.getInvitationMaxAge());
        return new RobotsGatedAdapter(dispatcher, gate, Clock.systemUTC());
    }

    @Test
    void namedSourceDeliversUsableEventsRightNow() {
        String id = System.getProperty(SOURCE_PROPERTY);
        IngestSource source = sourceById(id);

        log.info("Probe {}: handler={} confidence={} enabled={} legal_class={} url={}",
                source.id(), source.handler(), source.confidence(), source.enabled(),
                source.legalClass(), source.access().url());

        List<PrimaryEvent> events = dispatcher.fetch(source);

        // Der eigentliche Zweck: den Abruf SEHEN.
        for (PrimaryEvent event : events) {
            log.info("  {} — {} ({})", event.publishedAt(), event.title(), event.url());
        }

        // Ein leerer Feed ist der Normalfall des Scheiterns: URL umgezogen, Bot abgewiesen, XML
        // unparsbar. Der Fetcher schluckt all das zu Optional.empty() — hier muss es auffallen.
        assertThat(events)
                .as("Quelle '%s' (%s) liefert keine Einträge — URL/Zugang prüfen",
                        source.id(), source.access().url())
                .isNotEmpty();

        // Quellunabhängig: was der Ingest an jedem Ereignis braucht. Kein hartes legal_class 'A' wie
        // in PrimaryRssLiveIT — die Klasse soll für jeden Kandidaten taugen, auch für Klasse B.
        Instant jetzt = Instant.now();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.sourceId()).isEqualTo(source.id());
            assertThat(event.url()).startsWith("http");
            assertThat(event.title()).isNotBlank();
            assertThat(event.publishedAt()).isNotNull();
            assertThat(event.publishedAt()).isBefore(jetzt.plusSeconds(3600)); // nicht aus der Zukunft
            assertThat(event.publishedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
            assertThat(event.fetchedAt()).isNotNull();
            assertThat(event.legalClass()).isEqualTo(source.legalClass());
            assertThat(event.attribution()).isEqualTo(source.attribution());
        });
    }

    /** Die Quelle per ID — über ALLE Quellen, nicht nur die aktivierten. */
    private IngestSource sourceById(String id) {
        List<IngestSource> alle = registry.load().ingestSources();
        return alle.stream()
                .filter(source -> source.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Quelle '" + id + "' steht nicht im Manifest. Bekannte IDs: "
                                + alle.stream().map(IngestSource::id).toList()));
    }
}
