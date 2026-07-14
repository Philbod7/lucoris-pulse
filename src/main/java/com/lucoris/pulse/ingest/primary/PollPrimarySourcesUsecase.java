package com.lucoris.pulse.ingest.primary;

import com.lucoris.pulse.core.domain.PrimarySourceState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ein Poll-Tick: ermittelt aus Manifest + persistiertem Quellen-Zustand die fälligen Quellen und
 * ruft sie sequenziell über {@link IngestPrimarySourcesUsecase#runSource} ab. Sequenziell ist
 * Absicht: 6 Quellen brauchen keine Parallelität, und ein Abruf nach dem anderen ist die
 * natürliche Höflichkeit gegenüber den Herausgebern.
 *
 * <p>Usecase-POJO ohne Spring-Annotationen — der {@code @Scheduled}-Trigger sitzt in der
 * Infrastruktur ({@code PrimaryPollingConfig}) und delegiert nur hierher. {@code runSource} wirft
 * nie, eine defekte Quelle bricht den Tick also nicht ab; ihr Fehlerzustand steht danach in
 * {@code primary_source_state}.
 */
public final class PollPrimarySourcesUsecase {

    private static final Logger log = LoggerFactory.getLogger(PollPrimarySourcesUsecase.class);

    private final PrimarySourceManifestLoader registry;
    private final SourceStateStore states;
    private final PollSchedule schedule;
    private final IngestPrimarySourcesUsecase ingest;
    private final Clock clock;

    public PollPrimarySourcesUsecase(
            PrimarySourceManifestLoader registry,
            SourceStateStore states,
            PollSchedule schedule,
            IngestPrimarySourcesUsecase ingest,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.states = Objects.requireNonNull(states, "states");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Führt einen Tick aus und liefert die Ergebnisse der tatsächlich abgerufenen Quellen. */
    public List<SourceRunResult> tick() {
        Instant now = clock.instant();
        Map<String, PrimarySourceState> stateById = states.loadAll();

        List<SourceRunResult> results = new ArrayList<>();
        for (IngestSource source : registry.enabledSources()) {
            if (schedule.isDue(source, stateById.get(source.id()), now)) {
                results.add(ingest.runSource(source));
            }
        }

        if (!results.isEmpty()) {
            log.info("Poll-Tick: {} Quelle(n) abgerufen — {}", results.size(),
                    results.stream()
                            .map(r -> r.sourceId() + (r.failed()
                                    ? " FEHLER"
                                    : " " + r.fetched() + "/" + r.newItems() + "/" + r.deduped()))
                            .toList());
        }
        return List.copyOf(results);
    }
}
