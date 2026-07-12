package com.lucoris.pulse.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Beweist, dass die Flyway-Migration {@code V2__url_index.sql} vollständig gegen ein echtes
 * PostgreSQL durchläuft: Tabelle {@code url_index} plus die beiden btree-Indexe existieren, und
 * die Tabelle trägt BEWUSST keinen Primary Key (append-only, Dubletten erlaubt).
 */
class FlywayV2UrlIndexIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayV2AppliedSuccessfully() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class);
        assertThat(success)
                .as("Flyway-History muss Version 2 als erfolgreich angewandt führen")
                .isTrue();
    }

    @Test
    void urlIndexTableAndIndexesExist() {
        assertRelationExists("url_index");
        assertRelationExists("ix_url_index_event");
        assertRelationExists("ix_url_index_url");
    }

    @Test
    void urlIndexHasNoPrimaryKey() {
        Integer pkCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conrelid = 'public.url_index'::regclass"
                        + " AND contype = 'p'",
                Integer.class);
        assertThat(pkCount)
                .as("url_index ist bewusst PK-los (append-only, Dubletten erlaubt)")
                .isZero();
    }

    /** {@code to_regclass} liefert für jede existierende Relation (Tabelle, Index, …) den OID-Namen, sonst NULL. */
    private void assertRelationExists(String relation) {
        String resolved = jdbc.queryForObject(
                "SELECT to_regclass('public." + relation + "')::text", String.class);
        assertThat(resolved)
                .as("Relation '%s' muss nach V2 existieren", relation)
                .isEqualTo(relation);
    }
}
