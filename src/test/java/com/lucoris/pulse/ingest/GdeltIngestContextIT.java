package com.lucoris.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.ingest.gdelt.FirehoseStore;
import com.lucoris.pulse.ingest.gdelt.GdeltIngestService;
import com.lucoris.pulse.ingest.gdelt.GdeltSliceClient;
import com.lucoris.pulse.ingest.gdelt.IngestGdeltDayUsecase;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verdrahtungstest (kein Netz): lädt den Spring-Context unter Profil {@code ingest} und prüft,
 * dass alle Ingest-Beans (Adapter, Usecase-POJO, Service, Clock) auflösbar sind. Läuft in
 * {@code mvn verify} mit und hält den Ingest-Pfad als echten Wiring-Gate — im Unterschied zum
 * live-abrufenden {@link GdeltLiveDayIngestIT}, der aus der CI ausgeschlossen ist.
 */
@ActiveProfiles("ingest")
class GdeltIngestContextIT extends AbstractPostgresIT {

    @Autowired GdeltIngestService service;
    @Autowired GdeltSliceClient client;
    @Autowired FirehoseStore store;
    @Autowired IngestGdeltDayUsecase usecase;
    @Autowired Clock clock;

    @Test
    void ingestBeansLoadUnderIngestProfile() {
        assertThat(service).isNotNull();
        assertThat(client).isNotNull();
        assertThat(store).isNotNull();
        assertThat(usecase).isNotNull();
        assertThat(clock).isNotNull();
    }
}
