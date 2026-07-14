package com.lucoris.pulse.ingest.config;

import com.lucoris.pulse.ingest.primary.IngestPrimarySourcesUsecase;
import com.lucoris.pulse.ingest.primary.PollPrimarySourcesUsecase;
import com.lucoris.pulse.ingest.primary.PollSchedule;
import com.lucoris.pulse.ingest.primary.PrimarySourceManifestLoader;
import com.lucoris.pulse.ingest.primary.SourceStateStore;
import com.lucoris.pulse.ingest.primary.robots.RobotsGate;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Der periodische Quellen-Poller — die EINZIGE Stelle, die den Primärquellen-Ingest von selbst
 * laufen lässt. Doppelt verriegelt:
 *
 * <ul>
 *   <li>{@code @Profile("ingest")} — nur das Ingest-Deployment pollt.
 *   <li>{@code @ConditionalOnProperty(poll.enabled, Default AUS)} — Profil-Gating allein genügt
 *       NICHT: mehrere Integrationstests aktivieren {@code ingest}. Ohne die Property existiert
 *       diese Konfiguration samt {@code @EnableScheduling} nicht — kein Scheduler-Thread, kein
 *       automatischer Netzzugriff in Tests.
 * </ul>
 *
 * <p>{@code @EnableScheduling} lebt bewusst NUR auf dieser konditionalen Klasse.
 * Einschalten in Produktion: {@code LUCORIS_INGEST_PRIMARY_POLL_ENABLED=true}.
 */
@Configuration
@Profile("ingest")
@EnableScheduling
@ConditionalOnProperty(name = "lucoris.ingest.primary.poll.enabled", havingValue = "true")
public class PrimaryPollingConfig {

    @Bean
    PollSchedule pollSchedule(RobotsGate robotsGate) {
        return new PollSchedule(robotsGate);
    }

    @Bean
    PollPrimarySourcesUsecase pollPrimarySourcesUsecase(
            PrimarySourceManifestLoader primarySourceManifestLoader,
            SourceStateStore sourceStateStore,
            PollSchedule pollSchedule,
            IngestPrimarySourcesUsecase ingestPrimarySourcesUsecase) {
        return new PollPrimarySourcesUsecase(primarySourceManifestLoader, sourceStateStore,
                pollSchedule, ingestPrimarySourcesUsecase, Clock.systemUTC());
    }

    @Bean
    PrimarySourcePoller primarySourcePoller(PollPrimarySourcesUsecase pollPrimarySourcesUsecase) {
        return new PrimarySourcePoller(pollPrimarySourcesUsecase);
    }

    /**
     * Dünner Trigger ohne Logik: der Tick selbst (Fälligkeit, Abruf, Fehlerisolation) lebt im
     * Spring-freien {@link PollPrimarySourcesUsecase}. {@code fixedDelay} statt {@code fixedRate}:
     * der nächste Tick startet erst NACH dem Ende des vorigen — Läufe überlappen nie.
     */
    static final class PrimarySourcePoller {

        private final PollPrimarySourcesUsecase poll;

        PrimarySourcePoller(PollPrimarySourcesUsecase poll) {
            this.poll = poll;
        }

        @Scheduled(fixedDelayString = "${lucoris.ingest.primary.poll.tick-interval:30s}")
        void tick() {
            poll.tick();
        }
    }
}
