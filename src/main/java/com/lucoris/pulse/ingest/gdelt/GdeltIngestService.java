package com.lucoris.pulse.ingest.gdelt;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Dünne Service-Fassade über den {@link IngestGdeltDayUsecase} — enthält keine Geschäftslogik,
 * nur Delegation. Einstiegspunkt für einen späteren {@code @Scheduled}-Poller. Nur unter Profil
 * {@code ingest} aktiv.
 */
@Service
@Profile("ingest")
public class GdeltIngestService {

    private final IngestGdeltDayUsecase usecase;
    private final Clock clock;

    public GdeltIngestService(IngestGdeltDayUsecase usecase, Clock clock) {
        this.usecase = usecase;
        this.clock = clock;
    }

    /** Ruft den angegebenen UTC-Tag ab und schreibt ihn in die DB. */
    public DayIngestReport ingestDay(LocalDate dayUtc) {
        return usecase.ingestDay(dayUtc);
    }

    /** Ruft den Vortag (relativ zur UTC-Uhr) ab. */
    public DayIngestReport ingestYesterday() {
        return usecase.ingestDay(LocalDate.now(clock).minusDays(1));
    }
}
