package com.lucoris.pulse.ingest.gdelt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reiner Unit-Test der Bereichs-Iteration von {@link IngestGdeltDayUsecase#ingestRange} — ohne
 * Spring, Netz oder DB. Die beiden Ports sind In-Memory-Fakes: der Client liefert für jeden Slice
 * {@link Optional#empty()} (nichts abrufbar) und protokolliert die angefragten Slice-Zeitstempel,
 * der Store schreibt nichts und findet keine fehlenden Events. So bleibt nur die reine Tages-Schleife
 * ({@code von} inklusive, {@code bis} exklusive) übrig — genau das, was hier geprüft wird.
 */
class IngestGdeltRangeUsecaseTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);
    private static final int SLICES_PER_DAY = 96;
    /** Uhr weit nach den Testtagen — so liegen alle Slices in der Vergangenheit (kein Zeit-Cutoff). */
    private static final Clock FAR_FUTURE = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);

    private RecordingClient client;
    private IngestGdeltDayUsecase usecase;

    @BeforeEach
    void setUp() {
        client = new RecordingClient();
        usecase = newUsecase(FAR_FUTURE);
    }

    private IngestGdeltDayUsecase newUsecase(Clock clock) {
        return new IngestGdeltDayUsecase(
                client,
                new NoopStore(),
                new GdeltEventRowMapper(),
                new GdeltMentionRowMapper(),
                new GdeltGkgRowMapper(),
                new MarketRelevanceFilter(List.of()),
                false, // logThemeHistogram
                true,  // filterLinkedEventsAndMentions
                1,     // eventBackfillRetries
                clock);
    }

    @Test
    void ingestRangeReadsEveryDayInHalfOpenIntervalVonInclusiveBisExclusive() {
        RangeIngestReport report = usecase.ingestRange(DAY, DAY.plusDays(3));

        assertThat(report.vonInclusive()).isEqualTo(DAY);
        assertThat(report.bisExclusive()).isEqualTo(DAY.plusDays(3));
        assertThat(report.dayCount()).isEqualTo(3);
        assertThat(report.days()).extracting(DayIngestReport::day)
                .containsExactly(DAY, DAY.plusDays(1), DAY.plusDays(2));

        // Slices spannen lückenlos von DAY 00:00 bis DAY+2 23:45 (bis-Tag NICHT enthalten).
        assertThat(client.requested).hasSize(3 * SLICES_PER_DAY);
        assertThat(client.requested.get(0)).isEqualTo(DAY.atStartOfDay());
        assertThat(client.requested.get(client.requested.size() - 1))
                .isEqualTo(DAY.plusDays(2).atTime(23, 45));
    }

    @Test
    void bisNullReadsExactlyTheSingleDayVonFully() {
        RangeIngestReport report = usecase.ingestRange(DAY, null);

        assertThat(report.bisExclusive()).isEqualTo(DAY.plusDays(1)); // bis = von + 1 Tag
        assertThat(report.dayCount()).isEqualTo(1);
        assertThat(report.days()).extracting(DayIngestReport::day).containsExactly(DAY);

        assertThat(client.requested).hasSize(SLICES_PER_DAY);
        assertThat(client.requested.get(0)).isEqualTo(DAY.atStartOfDay());
        assertThat(client.requested.get(SLICES_PER_DAY - 1)).isEqualTo(DAY.atTime(23, 45));
    }

    @Test
    void bisEqualToVonIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> usecase.ingestRange(DAY, DAY));
        assertThat(client.requested).isEmpty();
    }

    @Test
    void bisBeforeVonIsRejected() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> usecase.ingestRange(DAY, DAY.minusDays(1)));
        assertThat(client.requested).isEmpty();
    }

    @Test
    void vonNullIsRejected() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> usecase.ingestRange(null, DAY));
    }

    @Test
    void ingestDayStopsAtCurrentTimeAndSkipsFutureSlices() {
        // Uhr auf DAY 00:30 fixiert -> nur die Slices 00:00, 00:15, 00:30 dürfen abgerufen werden;
        // 00:45 und später liegen in der Zukunft und werden NICHT mehr angefragt.
        Clock at0030 = Clock.fixed(DAY.atTime(0, 30).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

        newUsecase(at0030).ingestDay(DAY);

        assertThat(client.requested)
                .containsExactly(DAY.atStartOfDay(), DAY.atTime(0, 15), DAY.atTime(0, 30));
    }

    /** Fake-Client: protokolliert angefragte Slices, liefert stets {@link Optional#empty()}. */
    private static final class RecordingClient implements GdeltSliceClient {
        private final List<LocalDateTime> requested = new ArrayList<>();

        @Override
        public Optional<List<String[]>> download(GdeltDataset dataset, LocalDateTime sliceStartUtc) {
            requested.add(sliceStartUtc);
            return Optional.empty();
        }
    }

    /** Fake-Store: schreibt nichts, kennt keine bereits verarbeiteten Dateien, findet nichts Fehlendes. */
    private static final class NoopStore implements FirehoseStore {
        @Override
        public int insertEvents(List<GdeltEvent> rows) {
            return 0;
        }

        @Override
        public int insertMentions(List<GdeltMention> rows) {
            return 0;
        }

        @Override
        public int insertGkg(List<GdeltGkg> rows) {
            return 0;
        }

        @Override
        public int insertAtomic(List<?> rows) {
            return 0;
        }

        @Override
        public boolean isFileProcessed(String filename) {
            return false;
        }

        @Override
        public List<MissingEventRef> findMissingEventRefs(Instant sliceStart, Instant sliceEndExcl) {
            return List.of();
        }
    }
}
