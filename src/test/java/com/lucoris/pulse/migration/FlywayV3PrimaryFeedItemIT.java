package com.lucoris.pulse.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Beweist, dass die Flyway-Migration {@code V3__primary_feed_item.sql} vollständig gegen ein
 * echtes PostgreSQL durchläuft: beide Tabellen, der UNIQUE-Dedup-Index, die Read-Model-Indexe
 * und die Sequence mit INCREMENT 50 (= Hibernate allocationSize, pooled-lo) existieren.
 * Additive Migration — Test gegen leeres Schema genügt.
 */
class FlywayV3PrimaryFeedItemIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayV3AppliedSuccessfully() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '3'", Boolean.class);
        assertThat(success)
                .as("Flyway-History muss Version 3 als erfolgreich angewandt führen")
                .isTrue();
    }

    @Test
    void tablesIndexesAndSequenceExist() {
        assertRelationExists("primary_feed_item");
        assertRelationExists("primary_source_state");
        assertRelationExists("primary_feed_item_seq");
        assertRelationExists("ux_primary_feed_item_dedup_key");
        assertRelationExists("ix_primary_feed_item_published");
        assertRelationExists("ix_primary_feed_item_source_published");
    }

    @Test
    void dedupKeyIndexIsUnique() {
        Boolean unique = jdbc.queryForObject(
                "SELECT indisunique FROM pg_index"
                        + " WHERE indexrelid = 'public.ux_primary_feed_item_dedup_key'::regclass",
                Boolean.class);
        assertThat(unique)
                .as("Der Dedup-Index ist das harte Sicherheitsnetz und MUSS unique sein")
                .isTrue();
    }

    @Test
    void sequenceIncrementMatchesHibernateAllocationSize() {
        Long increment = jdbc.queryForObject(
                "SELECT increment_by FROM pg_sequences"
                        + " WHERE schemaname = 'public' AND sequencename = 'primary_feed_item_seq'",
                Long.class);
        assertThat(increment)
                .as("INCREMENT der Sequence muss der Hibernate-allocationSize (50) entsprechen")
                .isEqualTo(50L);
    }

    @Test
    void sourceStateHasNaturalPrimaryKeyWithoutSequence() {
        Integer pkCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint"
                        + " WHERE conrelid = 'public.primary_source_state'::regclass AND contype = 'p'",
                Integer.class);
        assertThat(pkCount).as("source_id ist natürlicher PK").isEqualTo(1);

        String defaultExpr = jdbc.queryForObject(
                "SELECT coalesce(column_default, '') FROM information_schema.columns"
                        + " WHERE table_name = 'primary_source_state' AND column_name = 'source_id'",
                String.class);
        assertThat(defaultExpr).as("natürlicher Schlüssel trägt KEINE Sequence").isEmpty();
    }

    /** {@code to_regclass} liefert für jede existierende Relation (Tabelle, Index, Sequence, …) den Namen, sonst NULL. */
    private void assertRelationExists(String relation) {
        String resolved = jdbc.queryForObject(
                "SELECT to_regclass('public." + relation + "')::text", String.class);
        assertThat(resolved)
                .as("Relation '%s' muss nach V3 existieren", relation)
                .isEqualTo(relation);
    }
}
