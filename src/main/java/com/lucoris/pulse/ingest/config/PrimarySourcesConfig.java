package com.lucoris.pulse.ingest.config;

import com.lucoris.pulse.core.domain.PrimarySourceState;
import com.lucoris.pulse.ingest.primary.AdapterDispatcher;
import com.lucoris.pulse.ingest.primary.FeedItemStore;
import com.lucoris.pulse.ingest.primary.GenericRssAdapter;
import com.lucoris.pulse.ingest.primary.LastSuccessLookup;
import com.lucoris.pulse.ingest.primary.SourceStateStore;
import com.lucoris.pulse.ingest.primary.TdmAwareFeedFetcher;
import com.lucoris.pulse.ingest.primary.adapter.HttpFeedFetcher;
import com.lucoris.pulse.ingest.primary.IngestPrimarySourcesUsecase;
import com.lucoris.pulse.ingest.primary.PrimarySourceManifestLoader;
import com.lucoris.pulse.ingest.primary.RobotsGatedAdapter;
import com.lucoris.pulse.ingest.primary.SecEdgarAdapter;
import com.lucoris.pulse.ingest.primary.SecEdgarCikLoader;
import com.lucoris.pulse.ingest.primary.SecEdgarDailyIndexAdapter;
import com.lucoris.pulse.ingest.primary.SourceLoadValidator;
import com.lucoris.pulse.ingest.primary.robots.CachingRobotsGate;
import com.lucoris.pulse.ingest.primary.robots.InvitationVerifier;
import com.lucoris.pulse.ingest.primary.robots.PolicyFetcher;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verdrahtung des Primärquellen-Ingests. Die POJOs (Loader, Adapter) bleiben Spring-frei und
 * werden hier per Konstruktor zusammengesteckt.
 *
 * <p>{@code @Profile({"ingest", "validate-sources"})} ist ODER-Semantik: die Beans stehen in beiden
 * Profilen, denn die Load-Validierung braucht denselben Loader und denselben Adapter wie der Ingest.
 *
 * <p>Die {@link Clock} wird bewusst NICHT als Bean injiziert, sondern hier direkt gesetzt:
 * {@code IngestConfig} definiert bereits ein Bean {@code ingestClock}, das aber nur unter Profil
 * {@code ingest} existiert. Ein zweites Clock-Bean würde bei gleichzeitig aktiven Profilen
 * kollidieren, ein injiziertes fehlte unter reinem {@code validate-sources}. Die POJOs nehmen die
 * Uhr weiterhin über den Konstruktor — im Unit-Test eine {@code Clock.fixed(...)}.
 */
@Configuration
@Profile({"ingest", "validate-sources"})
@EnableConfigurationProperties(PrimarySourceProperties.class)
public class PrimarySourcesConfig {

    /**
     * Eigener Jackson-Mapper statt Springs Bean: dessen Konfiguration ist über Boot-Properties
     * fremdveränderbar, das Manifest-Parsing soll aber in Test und Produktion identisch sein.
     */
    @Bean
    PrimarySourceManifestLoader primarySourceManifestLoader(PrimarySourceProperties props) {
        return new PrimarySourceManifestLoader(JsonMapper.builder().build(), props.getManifest());
    }

    /**
     * Der dritte Vorbehaltskanal, als Dekorator um den HTTP-Fetcher: erklärt die Feed-Antwort einen
     * TDM-Vorbehalt im Header, wird das Dokument verworfen, bevor der Parser es sieht. Kein Adapter
     * kann das umgehen — er bekommt gar nichts anderes injiziert.
     */
    @Bean
    TdmAwareFeedFetcher tdmAwareFeedFetcher(HttpFeedFetcher httpFeedFetcher) {
        return new TdmAwareFeedFetcher(httpFeedFetcher);
    }

    @Bean
    GenericRssAdapter genericRssAdapter(TdmAwareFeedFetcher tdmAwareFeedFetcher) {
        return new GenericRssAdapter(tdmAwareFeedFetcher, Clock.systemUTC());
    }

    /** Die kuratierte CIK-Watchlist des Echtzeit-EDGAR-Pfads (eigener Mapper, gleiche Begründung). */
    @Bean
    SecEdgarCikLoader secEdgarCikLoader(PrimarySourceProperties props) {
        return new SecEdgarCikLoader(JsonMapper.builder().build(), props.getSecEdgar().getCiks());
    }

    /**
     * Handler {@code sec_edgar}: die EDGAR-submissions-API (Echtzeit, je Firma der Watchlist).
     * Teilt sich mit dem RSS-Adapter den {@link TdmAwareFeedFetcher} — der TDM-Header-Kanal gilt
     * damit auch hier.
     */
    @Bean
    SecEdgarAdapter secEdgarAdapter(TdmAwareFeedFetcher tdmAwareFeedFetcher,
            SecEdgarCikLoader secEdgarCikLoader, PrimarySourceProperties props) {
        return new SecEdgarAdapter(tdmAwareFeedFetcher, secEdgarCikLoader, JsonMapper.builder().build(),
                Clock.systemUTC(), props.getSecEdgar().getPacing(), props.getSecEdgar().getLookback());
    }

    /**
     * Handler {@code sec_edgar_daily}: der EDGAR-Tagesindex — das Netz unter dem Echtzeit-Pfad, das
     * auch Einreichungen von Firmen AUSSERHALB der Watchlist auffängt (dafür erst am Abend und ohne
     * Uhrzeit).
     */
    @Bean
    SecEdgarDailyIndexAdapter secEdgarDailyIndexAdapter(TdmAwareFeedFetcher tdmAwareFeedFetcher,
            LastSuccessLookup lastSuccessLookup, PrimarySourceProperties props) {
        return new SecEdgarDailyIndexAdapter(tdmAwareFeedFetcher, lastSuccessLookup, Clock.systemUTC(),
                props.getSecEdgar().getDailyIndexMaxDays());
    }

    /**
     * Woher der Tagesindex weiß, wie weit er zurücklesen muss: aus dem persistierten Quellen-Zustand.
     * Bewusst die DB und kein Feld im Adapter — ein Wert im Arbeitsspeicher wäre nach einem Neustart
     * weg, also genau im Ausfall-Fall, für den die lange Rückschau existiert.
     */
    @Bean
    @Profile("ingest")
    LastSuccessLookup lastSuccessLookup(SourceStateStore sourceStateStore) {
        return sourceId -> Optional.ofNullable(sourceStateStore.loadAll().get(sourceId))
                .map(PrimarySourceState::getLastSuccessAt);
    }

    /**
     * Unter reinem {@code validate-sources} gibt es die Stores nicht (sie hängen an {@code ingest}) —
     * ohne diesen Bean bräche die Load-Validierung an einer fehlenden Abhängigkeit. „Kein Zustand"
     * ist hier zugleich die inhaltlich richtige Antwort: eine Probe soll zeigen, was die Quelle
     * maximal hergibt, nicht ein inkrementelles Restfenster.
     */
    @Bean
    @Profile("!ingest")
    LastSuccessLookup fullWindowLookup() {
        return sourceId -> Optional.empty();
    }

    /** Routing-Tabelle {@code handler} -> Adapter. Weitere Handler ({@code html_index}, ...) kommen hier dazu. */
    @Bean
    AdapterDispatcher adapterDispatcher(GenericRssAdapter genericRssAdapter,
            SecEdgarAdapter secEdgarAdapter, SecEdgarDailyIndexAdapter secEdgarDailyIndexAdapter) {
        return new AdapterDispatcher(Map.of(
                GenericRssAdapter.HANDLER, genericRssAdapter,
                SecEdgarAdapter.HANDLER, secEdgarAdapter,
                SecEdgarDailyIndexAdapter.HANDLER, secEdgarDailyIndexAdapter));
    }

    /** robots.txt-/TDM-Prüfung mit Cache je Host. Fail-closed: keine Auskunft = kein Abruf. */
    @Bean
    RobotsGate robotsGate(PolicyFetcher policyFetcher, PrimarySourceProperties props) {
        return new CachingRobotsGate(
                policyFetcher,
                JsonMapper.builder().build(),
                props.getUserAgent(),
                props.getRobotsSuccessTtl(),
                props.getRobotsFailureTtl(),
                props.getRobotsCacheMaxHosts(),
                Clock.systemUTC(),
                props.getInvitationMaxAge());
    }

    /**
     * DAS Sicherheitsnetz: der Gate-Dekorator liegt VOR dem Dispatcher, nicht dahinter. Jeder
     * Zugriff auf eine Quelle — heute RSS, morgen sec_edgar — läuft dadurch zwangsläufig durch die
     * Erlaubnisprüfung. Usecase und Validator bekommen ausschließlich diesen Adapter injiziert,
     * nie den nackten Dispatcher.
     */
    @Bean
    RobotsGatedAdapter gatedSourceAdapter(AdapterDispatcher adapterDispatcher, RobotsGate robotsGate) {
        return new RobotsGatedAdapter(adapterDispatcher, robotsGate, Clock.systemUTC());
    }

    /**
     * Nur unter {@code ingest}: die Stores (StatelessSession) existieren unter reinem
     * {@code validate-sources} nicht — der Validator braucht den Usecase auch nicht, er nimmt
     * den gated Adapter direkt.
     */
    @Bean
    @Profile("ingest")
    IngestPrimarySourcesUsecase ingestPrimarySourcesUsecase(
            PrimarySourceManifestLoader primarySourceManifestLoader,
            RobotsGatedAdapter gatedSourceAdapter,
            FeedItemStore feedItemStore,
            SourceStateStore sourceStateStore) {
        return new IngestPrimarySourcesUsecase(primarySourceManifestLoader, gatedSourceAdapter,
                feedItemStore, sourceStateStore, Clock.systemUTC());
    }

    /**
     * Der EINZIGE Punkt im Primärquellen-Pfad, der von selbst läuft — und er hängt an
     * {@code validate-sources}. Unter {@code ingest} allein passiert beim Boot nichts: der Ingest
     * wird aufgerufen, er startet nicht. Damit können die Standard-Tests kein Netz erreichen.
     */
    @Bean
    @Profile("validate-sources")
    ApplicationRunner sourceLoadValidationRunner(
            PrimarySourceManifestLoader primarySourceManifestLoader,
            RobotsGatedAdapter gatedSourceAdapter,
            PolicyFetcher policyFetcher,
            RobotsGate robotsGate) {
        // Der InvitationVerifier ruft die Einladungsseite ab — deshalb hängt er hier und NICHT im
        // Ingest-Pfad: der würde sonst bei jedem Poll eine HTML-Seite mitziehen.
        SourceLoadValidator validator = new SourceLoadValidator(
                primarySourceManifestLoader, gatedSourceAdapter, Clock.systemUTC(),
                new InvitationVerifier(policyFetcher), robotsGate);
        return args -> validator.validate();
    }
}
