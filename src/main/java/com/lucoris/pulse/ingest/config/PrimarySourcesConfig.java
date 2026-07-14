package com.lucoris.pulse.ingest.config;

import com.lucoris.pulse.ingest.primary.AdapterDispatcher;
import com.lucoris.pulse.ingest.primary.FeedFetcher;
import com.lucoris.pulse.ingest.primary.GenericRssAdapter;
import com.lucoris.pulse.ingest.primary.IngestPrimarySourcesUsecase;
import com.lucoris.pulse.ingest.primary.PrimarySourceManifestLoader;
import com.lucoris.pulse.ingest.primary.SourceLoadValidator;
import java.time.Clock;
import java.util.Map;
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

    @Bean
    GenericRssAdapter genericRssAdapter(FeedFetcher feedFetcher) {
        return new GenericRssAdapter(feedFetcher, Clock.systemUTC());
    }

    /** Routing-Tabelle {@code handler} -> Adapter. Weitere Handler (sec_edgar, ...) kommen hier dazu. */
    @Bean
    AdapterDispatcher adapterDispatcher(GenericRssAdapter genericRssAdapter) {
        return new AdapterDispatcher(Map.of(GenericRssAdapter.HANDLER, genericRssAdapter));
    }

    @Bean
    IngestPrimarySourcesUsecase ingestPrimarySourcesUsecase(
            PrimarySourceManifestLoader primarySourceManifestLoader, AdapterDispatcher adapterDispatcher) {
        return new IngestPrimarySourcesUsecase(primarySourceManifestLoader, adapterDispatcher);
    }

    /**
     * Der EINZIGE Punkt im Primärquellen-Pfad, der von selbst läuft — und er hängt an
     * {@code validate-sources}. Unter {@code ingest} allein passiert beim Boot nichts: der Ingest
     * wird aufgerufen, er startet nicht. Damit können die Standard-Tests kein Netz erreichen.
     */
    @Bean
    @Profile("validate-sources")
    ApplicationRunner sourceLoadValidationRunner(
            PrimarySourceManifestLoader primarySourceManifestLoader, AdapterDispatcher adapterDispatcher) {
        SourceLoadValidator validator =
                new SourceLoadValidator(primarySourceManifestLoader, adapterDispatcher, Clock.systemUTC());
        return args -> validator.validate();
    }
}
