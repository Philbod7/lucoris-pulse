package com.lucoris.pulse.ingest.primary;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Liest alle aktivierten Primärquellen ({@code enabled: true}) über den {@link AdapterDispatcher}
 * und liefert die gesammelten {@link PrimaryEvent}s.
 *
 * <p>Usecase-POJO ohne Spring-Annotationen. Bewusst KEIN {@code ApplicationRunner}/
 * {@code CommandLineRunner} und kein {@code @Scheduled}: dieser Ingest startet nicht von selbst
 * beim Boot. Der Poller kommt später und wird {@link #run()} aufrufen.
 *
 * <p>Noch ohne Persistenz — die Ereignisse werden zurückgegeben und geloggt. Die Flyway-Tabelle
 * und das Schreiben folgen im nächsten Schritt.
 */
public final class IngestPrimarySourcesUsecase {

    private static final Logger log = LoggerFactory.getLogger(IngestPrimarySourcesUsecase.class);

    private final PrimarySourceManifestLoader registry;
    private final SourceAdapter dispatcher;

    public IngestPrimarySourcesUsecase(PrimarySourceManifestLoader registry, SourceAdapter dispatcher) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /** Ruft jede aktivierte Quelle einmal ab. Eine defekte Quelle bricht den Lauf NICHT ab. */
    public List<PrimaryEvent> run() {
        List<IngestSource> sources = registry.enabledSources();
        log.info("Primärquellen-Ingest startet: {} aktivierte Quelle(n) {}",
                sources.size(), sources.stream().map(IngestSource::id).toList());

        List<PrimaryEvent> events = new ArrayList<>();
        for (IngestSource source : sources) {
            try {
                events.addAll(dispatcher.fetch(source));
            } catch (RuntimeException e) {
                // Ein nicht implementierter Handler oder ein kaputter Feed darf die übrigen Quellen
                // nicht mitreißen — laut protokollieren, weitermachen.
                log.error("Quelle {} fehlgeschlagen ({}) — übrige Quellen laufen weiter",
                        source.id(), e.toString());
            }
        }

        log.info("Primärquellen-Ingest fertig: {} Ereignis(se) aus {} Quelle(n)",
                events.size(), sources.size());
        for (PrimaryEvent event : events) {
            log.info("  [{}] {} — {} ({})",
                    event.sourceId(), event.publishedAt(), event.title(), event.url());
        }
        return List.copyOf(events);
    }
}
