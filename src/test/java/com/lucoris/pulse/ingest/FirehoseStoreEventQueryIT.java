package com.lucoris.pulse.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltMention;
import com.lucoris.pulse.ingest.gdelt.FirehoseStore;
import com.lucoris.pulse.ingest.gdelt.MissingEventRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Prüft die HQL von {@code findMissingEventRefs} gegen echtes PostgreSQL: liefert genau die
 * (globalEventId, eventTimeDate) der Mentions im Slice-Fenster, für die kein Event existiert.
 * StatelessSession committet außerhalb von Springs Rollback → TRUNCATE im Teardown.
 */
@ActiveProfiles("ingest")
class FirehoseStoreEventQueryIT extends AbstractPostgresIT {

    private static final Instant WINDOW_START = Instant.parse("2026-07-10T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-10T00:15:00Z");
    private static final Instant OLDER_SLICE = Instant.parse("2026-07-09T12:00:00Z");

    @Autowired FirehoseStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void truncate() {
        // Vor UND nach dem Test aufräumen: der Postgres-Container ist über alle IT geteilt und der
        // StatelessSession-Firehose committet außerhalb von Springs Rollback.
        // jdbc.execute("TRUNCATE TABLE gdelt_events, gdelt_mentions, gdelt_gkg, ingest_log CASCADE");
    }

    @Test
    void findsOnlyInWindowMentionsWithoutEvent() {
        // In-Fenster, Event vorhanden -> nicht fehlend
        store.insertMentions(List.of(mention(1001L, WINDOW_START, WINDOW_START)));
        store.insertEvents(List.of(event(1001L, WINDOW_START)));
        // In-Fenster, Event fehlt, verweist auf älteren Slice -> muss gefunden werden
        store.insertMentions(List.of(mention(1002L, OLDER_SLICE, WINDOW_START.plusSeconds(300))));
        // Außerhalb des Fensters -> ignorieren
        store.insertMentions(List.of(mention(1003L, WINDOW_START, WINDOW_END.plusSeconds(60))));

        List<MissingEventRef> missing = store.findMissingEventRefs(WINDOW_START, WINDOW_END);

        assertThat(missing).containsExactly(new MissingEventRef(1002L, OLDER_SLICE));
    }

    private static GdeltMention mention(long globalEventId, Instant eventTimeDate, Instant mentionTimeDate) {
        GdeltMention m = new GdeltMention();
        m.setGlobalEventId(globalEventId);
        m.setEventTimeDate(eventTimeDate);
        m.setMentionTimeDate(mentionTimeDate); // PK-Partitionsschlüssel (2026-07)
        return m;
    }

    private static GdeltEvent event(long globalEventId, Instant dateAdded) {
        GdeltEvent e = new GdeltEvent();
        e.setGlobalEventId(globalEventId);
        e.setDateAdded(dateAdded); // NOT NULL
        return e;
    }
}
