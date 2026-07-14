package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.core.domain.PrimaryFeedItem;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Liest Primärquellen über den {@link AdapterDispatcher} und speichert die Meldungen
 * quellenübergreifend dedupliziert (Schlüssel: {@link DedupKeys}) über den {@link FeedItemStore}.
 * Der Betriebszustand jeder Quelle wird nach jedem Lauf im {@link SourceStateStore} festgehalten —
 * hier und nicht im Poller, damit Einmal-Lauf und Poller dieselbe Buchführung teilen.
 *
 * <p>Usecase-POJO ohne Spring-Annotationen. Bewusst KEIN {@code ApplicationRunner}/
 * {@code CommandLineRunner} und kein {@code @Scheduled}: dieser Ingest startet nicht von selbst
 * beim Boot; der Poller ({@code PollPrimarySourcesUsecase}) ruft {@link #runSource} auf.
 *
 * <p>Wiederholte Läufe sind idempotent: schon gespeicherte Schlüssel werden vor dem Insert
 * ausgefiltert (select-then-insert; der UNIQUE-Index ist das Sicherheitsnetz darunter).
 */
public final class IngestPrimarySourcesUsecase {

    private static final Logger log = LoggerFactory.getLogger(IngestPrimarySourcesUsecase.class);

    private final PrimarySourceManifestLoader registry;
    private final SourceAdapter dispatcher;
    private final FeedItemStore items;
    private final SourceStateStore states;
    private final Clock clock;

    public IngestPrimarySourcesUsecase(
            PrimarySourceManifestLoader registry,
            SourceAdapter dispatcher,
            FeedItemStore items,
            SourceStateStore states,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.items = Objects.requireNonNull(items, "items");
        this.states = Objects.requireNonNull(states, "states");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Ruft jede aktivierte Quelle einmal ab. Eine defekte Quelle bricht den Lauf NICHT ab. */
    public PrimaryIngestReport runAll() {
        List<IngestSource> sources = registry.enabledSources();
        log.info("Primärquellen-Ingest startet: {} aktivierte Quelle(n) {}",
                sources.size(), sources.stream().map(IngestSource::id).toList());

        List<SourceRunResult> results = new ArrayList<>(sources.size());
        for (IngestSource source : sources) {
            results.add(runSource(source));
        }

        PrimaryIngestReport report = new PrimaryIngestReport(List.copyOf(results));
        log.info("Primärquellen-Ingest fertig: {} gefetcht, {} neu, {} dedupliziert, {} Fehler",
                report.totalFetched(), report.totalNew(), report.totalDeduped(),
                report.failures().size());
        return report;
    }

    /**
     * Ruft EINE Quelle ab und persistiert ihre neuen Meldungen. Wirft nie — jeder Fehler (Netz,
     * Parse, robots-Verbot, nicht implementierter Handler) wird als Fehler-Result geliefert und
     * im Quellen-Zustand festgehalten, damit die übrigen Quellen weiterlaufen.
     */
    public SourceRunResult runSource(IngestSource source) {
        try {
            List<FeedItem> fetched = dispatcher.fetch(source);

            // Batch-interne Dubletten (gleiche Meldung zweimal im selben Lauf) vorab kollabieren;
            // LinkedHashMap erhält die Feed-Reihenfolge.
            Map<String, PrimaryFeedItem> byKey = new LinkedHashMap<>();
            for (FeedItem item : fetched) {
                byKey.putIfAbsent(DedupKeys.keyFor(item), FeedItemMapper.toEntity(item));
            }

            Set<String> existing = items.existingDedupKeys(byKey.keySet());
            List<PrimaryFeedItem> neu = byKey.entrySet().stream()
                    .filter(entry -> !existing.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
            items.insert(neu);

            int deduped = fetched.size() - neu.size();
            states.recordSuccess(source.id(), clock.instant(), fetched.size(), neu.size(), deduped);
            log.info("Quelle {}: {} gefetcht, {} neu, {} dedupliziert",
                    source.id(), fetched.size(), neu.size(), deduped);
            return SourceRunResult.success(source.id(), fetched.size(), neu.size(), deduped);
        } catch (RuntimeException e) {
            // Ein nicht implementierter Handler oder ein kaputter Feed darf die übrigen Quellen
            // nicht mitreißen — laut protokollieren, Zustand festhalten, weitermachen.
            log.error("Quelle {} fehlgeschlagen ({}) — übrige Quellen laufen weiter",
                    source.id(), e.toString());
            recordFailureQuietly(source.id(), e.toString());
            return SourceRunResult.failure(source.id(), e.toString());
        }
    }

    /** Auch die Fehler-Buchführung selbst darf die übrigen Quellen nicht mitreißen (z.B. DB weg). */
    private void recordFailureQuietly(String sourceId, String error) {
        try {
            states.recordFailure(sourceId, clock.instant(), error);
        } catch (RuntimeException e) {
            log.error("Quelle {}: Fehlerzustand nicht speicherbar ({})", sourceId, e.toString());
        }
    }
}
