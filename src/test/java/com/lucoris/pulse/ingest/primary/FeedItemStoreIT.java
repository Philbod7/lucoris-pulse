package com.lucoris.pulse.ingest.primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.core.domain.PrimaryFeedItem;
import com.lucoris.pulse.core.domain.PrimarySourceState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Prüft beide Primärquellen-Stores gegen echtes PostgreSQL: Insert + Schlüssel-Lookup, den
 * UNIQUE-Index als Sicherheitsnetz und die Zustandsübergänge Erfolg -> Fehler -> Erfolg.
 * StatelessSession committet außerhalb von Springs Rollback -> TRUNCATE vor und nach jedem Test.
 */
@ActiveProfiles("ingest")
class FeedItemStoreIT extends AbstractPostgresIT {

    private static final Instant T1 = Instant.parse("2026-07-14T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-07-14T10:05:00Z");
    private static final Instant T3 = Instant.parse("2026-07-14T10:10:00Z");

    @Autowired FeedItemStore items;
    @Autowired SourceStateStore states;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void truncate() {
        jdbc.execute("TRUNCATE TABLE primary_feed_item, primary_source_state");
    }

    @Test
    void insertedKeysAreFoundExistingOnesOnly() {
        items.insert(List.of(item("https://ex.org/a"), item("https://ex.org/b")));

        Set<String> existing = items.existingDedupKeys(
                List.of("https://ex.org/a", "https://ex.org/b", "https://ex.org/neu"));

        assertThat(existing).containsExactlyInAnyOrder("https://ex.org/a", "https://ex.org/b");
        assertThat(items.existingDedupKeys(List.of())).isEmpty();
    }

    @Test
    void uniqueIndexIsTheBackstopAgainstDuplicateKeys() {
        items.insert(List.of(item("https://ex.org/a")));

        // Das select-then-insert des Usecase verhindert diesen Fall normal — passiert er doch,
        // MUSS die DB hart scheitern statt still eine Dublette zu speichern.
        assertThatExceptionOfType(ConstraintViolationException.class)
                .isThrownBy(() -> items.insert(List.of(item("https://ex.org/a"))));

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM primary_feed_item", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void surrogateIdsComeFromTheSequence() {
        items.insert(List.of(item("https://ex.org/a"), item("https://ex.org/b")));

        List<Long> ids = jdbc.queryForList(
                "SELECT primary_feed_item_id FROM primary_feed_item ORDER BY 1", Long.class);
        assertThat(ids).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void successResetsTheFailureStateAndKeepsLastErrorAt() {
        states.recordFailure("ecb-press", T1, "connect timeout");
        states.recordFailure("ecb-press", T2, "connect timeout");

        PrimarySourceState nachFehlern = states.loadAll().get("ecb-press");
        assertThat(nachFehlern.getConsecutiveFailures()).isEqualTo(2);
        assertThat(nachFehlern.getLastError()).isEqualTo("connect timeout");
        assertThat(nachFehlern.getLastErrorAt()).isEqualTo(T2);
        assertThat(nachFehlern.getLastSuccessAt()).isNull();

        states.recordSuccess("ecb-press", T3, 20, 2, 18);

        PrimarySourceState nachErfolg = states.loadAll().get("ecb-press");
        assertThat(nachErfolg.getConsecutiveFailures()).isZero();
        assertThat(nachErfolg.getLastError()).isNull();
        assertThat(nachErfolg.getLastErrorAt()).isEqualTo(T2); // bleibt als Historie stehen
        assertThat(nachErfolg.getLastAttemptAt()).isEqualTo(T3);
        assertThat(nachErfolg.getLastSuccessAt()).isEqualTo(T3);
        assertThat(nachErfolg.getLastFetched()).isEqualTo(20);
        assertThat(nachErfolg.getLastNew()).isEqualTo(2);
        assertThat(nachErfolg.getLastDeduped()).isEqualTo(18);
    }

    @Test
    void loadAllKeysBySourceIdAndMissingSourcesHaveNoRow() {
        states.recordSuccess("ecb-press", T1, 20, 20, 0);
        states.recordFailure("bundesbank-allgemein", T1, "HTTP 503");

        Map<String, PrimarySourceState> alle = states.loadAll();

        assertThat(alle).containsOnlyKeys("ecb-press", "bundesbank-allgemein");
        assertThat(alle.get("bundesbank-allgemein").getConsecutiveFailures()).isEqualTo(1);
    }

    private static PrimaryFeedItem item(String dedupKey) {
        PrimaryFeedItem row = new PrimaryFeedItem();
        row.setDedupKey(dedupKey);
        row.setSourceId("ecb-press");
        row.setInstitution("European Central Bank (EZB)");
        row.setTitle("Titel");
        row.setUrl(dedupKey);
        row.setGuid(dedupKey);
        row.setPublishedAt(T1);
        row.setFetchedAt(T2);
        row.setLegalClass("A");
        return row;
    }
}
