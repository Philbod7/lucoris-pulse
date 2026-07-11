package com.lucoris.pulse.ingest.config;

import com.lucoris.pulse.ingest.gdelt.FirehoseStore;
import com.lucoris.pulse.ingest.gdelt.GdeltEventRowMapper;
import com.lucoris.pulse.ingest.gdelt.GdeltGkgRowMapper;
import com.lucoris.pulse.ingest.gdelt.GdeltMentionRowMapper;
import com.lucoris.pulse.ingest.gdelt.GdeltSliceClient;
import com.lucoris.pulse.ingest.gdelt.IngestGdeltDayUsecase;
import com.lucoris.pulse.ingest.gdelt.MarketRelevanceFilter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Verdrahtung des Ingest-Pfads. Nur unter Profil {@code ingest} aktiv. Konstruiert das
 * Usecase-POJO aus den Port-Adaptern und den (zustandslosen) Row-Mappern; die Mapper bleiben
 * bewusst reine POJOs (kein Spring-Bean nötig).
 */
@Configuration
@Profile("ingest")
@EnableConfigurationProperties(GdeltProperties.class)
public class IngestConfig {

    /** UTC-Uhr für die „Vortag"-Berechnung (in Tests überschreibbar). */
    @Bean
    Clock ingestClock() {
        return Clock.systemUTC();
    }

    @Bean
    IngestGdeltDayUsecase ingestGdeltDayUsecase(
            GdeltSliceClient client, FirehoseStore store, GdeltProperties props) {
        return new IngestGdeltDayUsecase(
                client,
                store,
                new GdeltEventRowMapper(),
                new GdeltMentionRowMapper(),
                new GdeltGkgRowMapper(),
                new MarketRelevanceFilter(props.getMarketRelevantThemePrefixes()),
                props.isLogThemeHistogram());
    }
}
