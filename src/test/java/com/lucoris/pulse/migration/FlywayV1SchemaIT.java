package com.lucoris.pulse.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Beweist, dass die Flyway-Migration {@code V1__initial_schema.sql} vollständig gegen ein echtes
 * PostgreSQL durchläuft. Prüft die Flyway-History sowie stellvertretend die Existenz zentraler
 * Tabellen und der batch-optimierten Sequenzen.
 */
class FlywayV1SchemaIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayV1AppliedSuccessfully() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
        assertThat(success)
                .as("Flyway-History muss Version 1 als erfolgreich angewandt führen")
                .isTrue();
    }

    @Test
    void coreTablesExist() {
        assertRelationExists("article");
        assertRelationExists("organization");
        assertRelationExists("event_significance");
    }

    @Test
    void surrogateSequencesExist() {
        assertRelationExists("article_seq");
        assertRelationExists("organization_seq");
    }

    /** {@code to_regclass} liefert für jede existierende Relation (Tabelle, Sequenz, …) den OID-Namen, sonst NULL. */
    private void assertRelationExists(String relation) {
        String resolved = jdbc.queryForObject(
                "SELECT to_regclass('public." + relation + "')::text", String.class);
        assertThat(resolved)
                .as("Relation '%s' muss nach V1 existieren", relation)
                .isEqualTo(relation);
    }
}
