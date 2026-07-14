package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.ingest.config.PrimarySourceProperties;
import com.lucoris.pulse.ingest.primary.adapter.HttpFeedFetcher;
import com.lucoris.pulse.ingest.primary.adapter.HttpPolicyFetcher;
import com.lucoris.pulse.ingest.primary.robots.CachingRobotsGate;
import com.lucoris.pulse.ingest.primary.robots.FetchIntent;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live-Integrationstest gegen die ECHTEN Feeds ALLER aktivierten Quellen (heute: ECB und Fed).
 *
 * <p>Was „aktiviert" heißt, entscheidet allein die Registry ({@code enabled: true}) — dieser Test
 * liest sie und friert den heutigen Stand bewusst NICHT ein. Eine neue Quelle scharfzuschalten ist
 * damit eine reine Manifest-Änderung; der Test zieht von selbst mit und deckt sie ab.
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

    private final RobotsGatedAdapter gatedAdapter = gatedAdapter();

    private RobotsGatedAdapter gatedAdapter() {
        PrimarySourceProperties props = new PrimarySourceProperties();
        props.setConnectTimeout(Duration.ofSeconds(10));
        props.setRequestTimeout(Duration.ofSeconds(30));

        GenericRssAdapter rss = new GenericRssAdapter(new HttpFeedFetcher(props), Clock.systemUTC());
        AdapterDispatcher dispatcher = new AdapterDispatcher(Map.of(GenericRssAdapter.HANDLER, rss));

        // Der Live-Test läuft durch DASSELBE Gate wie die Produktion — er darf das Sicherheitsnetz
        // nicht umgehen. Nebeneffekt: er prüft gleich mit, ob die echten robots.txt der aktivierten
        // Quellen uns den Feed-Pfad tatsächlich erlauben. Persistenz ist hier bewusst außen vor
        // (kein Spring, keine DB) — den vollen Pfad inkl. Speichern prüft PrimaryIngestLiveIT.
        RobotsGate gate = new CachingRobotsGate(
                new HttpPolicyFetcher(props), JsonMapper.builder().build(), props.getUserAgent(),
                props.getRobotsSuccessTtl(), props.getRobotsFailureTtl(), props.getRobotsCacheMaxHosts(),
                Clock.systemUTC(), props.getInvitationMaxAge());

        return new RobotsGatedAdapter(dispatcher, gate, Clock.systemUTC());
    }

    @Test
    void robotsTxtOfEveryEnabledSourceActuallyPermitsOurBot() {
        // Wenn das hier rot wird, hat sich die Erlaubnislage einer aktiven Quelle geändert — dann
        // gehört sie in der Registry auf enabled:false, nicht der Test angepasst.
        PrimarySourceProperties props = new PrimarySourceProperties();
        RobotsGate gate = new CachingRobotsGate(
                new HttpPolicyFetcher(props), JsonMapper.builder().build(), props.getUserAgent(),
                props.getRobotsSuccessTtl(), props.getRobotsFailureTtl(), props.getRobotsCacheMaxHosts(),
                Clock.systemUTC(), props.getInvitationMaxAge());

        assertThat(registry.enabledSources()).allSatisfy(source -> {
            RobotsGate.Decision decision = gate.check(new FetchIntent(
                    source.id(), URI.create(source.access().url()),
                    source.access().type(), source.expressInvitation()));
            assertThat(decision.allowed())
                    .as("Quelle %s (%s): %s", source.id(), source.access().url(), decision.reason())
                    .isTrue();
        });
    }

    @Test
    void everyEnabledFeedDeliversUsableEventsRightNow() {
        // Welche Quellen aktiviert sind, sagt die Registry — nicht dieser Test. Sonst müsste er bei
        // jeder scharfgeschalteten Quelle angefasst werden, und genau das soll er ja absichern.
        Map<String, IngestSource> byId = registry.enabledSources().stream()
                .collect(Collectors.toMap(IngestSource::id, source -> source));

        List<FeedItem> events = new java.util.ArrayList<>();
        for (IngestSource source : registry.enabledSources()) {
            events.addAll(gatedAdapter.fetch(source));
        }

        assertThat(events).isNotEmpty();

        // JEDE aktivierte Quelle muss etwas geliefert haben. Eine, die nichts bringt, fällt hier
        // auf: ein nicht abrufbarer Feed liefert eine leere Liste und fehlt dann unten.
        assertThat(events).extracting(FeedItem::sourceId)
                .as("aktivierte Quelle(n) ohne einen einzigen Eintrag")
                .containsAll(byId.keySet());

        Instant jetzt = Instant.now();
        assertThat(events).allSatisfy(event -> {
            IngestSource source = byId.get(event.sourceId());
            assertThat(source)
                    .as("Ereignis mit sourceId '%s', die gar nicht aktiviert ist", event.sourceId())
                    .isNotNull();

            // Quellunabhängig: was der Ingest an jedem Ereignis braucht.
            assertThat(event.url()).startsWith("http");
            assertThat(event.title()).isNotBlank();
            assertThat(event.publishedAt()).isNotNull();
            assertThat(event.publishedAt()).isBefore(jetzt.plusSeconds(3600)); // nicht aus der Zukunft
            assertThat(event.publishedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
            assertThat(event.fetchedAt()).isNotNull();

            // Rechtslage und Attributionsformel hängen unverändert am Ereignis — das Rendering baut
            // daraus die Quellzeile. Geprüft wird gegen DIE QUELLE, aus der das Ereignis stammt,
            // nicht gegen eine hier eingefrorene Konstante: eine Klasse-B-Quelle scharfzuschalten
            // darf diesen Test nicht rot machen. Dass im Manifest die richtigen Werte stehen (z.B.
            // die ECB-Formel), sichert PrimarySourceManifestLoaderTest offline ab; hier geht es um
            // das Durchreichen.
            assertThat(event.legalClass()).isEqualTo(source.legalClass());
            assertThat(event.attribution()).isEqualTo(source.attribution());
        });
    }
}
