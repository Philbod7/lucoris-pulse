package com.lucoris.pulse.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.AbstractPostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roundtrip gegen das reale Schema: speichert eine {@link Organization} und liest sie wieder.
 * Beweist, dass die Sequence-Vergabe über {@code organization_seq} (pooled-lo, allocationSize 50)
 * funktioniert und das Entity-Mapping in beide Richtungen trägt. Der erfolgreiche Context-Start
 * beweist zusätzlich, dass {@code ddl-auto=validate} alle Entities gegen V1 akzeptiert.
 */
@Transactional
class OrganizationRoundtripIT extends AbstractPostgresIT {

    @PersistenceContext
    private EntityManager em;

    @Test
    void persistAssignsSequenceIdAndReadsBack() {
        Organization org = new Organization();
        org.setCanonicalName("European Central Bank");
        org.setOrgNorm("european central bank");
        org.setReviewed(false);

        em.persist(org);
        em.flush(); // erzwingt das INSERT -> Sequence-Vergabe passiert hier

        Long generatedId = org.getOrganizationId();
        assertThat(generatedId)
                .as("organization_seq muss beim Insert eine ID vergeben (pooled-lo)")
                .isNotNull();

        em.clear(); // Persistence-Context leeren -> echtes Wiederlesen aus der DB erzwingen

        Organization reloaded = em.find(Organization.class, generatedId);
        assertThat(reloaded).as("gespeicherte Organisation muss wieder gelesen werden").isNotNull();
        assertThat(reloaded.getCanonicalName()).isEqualTo("European Central Bank");
        assertThat(reloaded.getOrgNorm()).isEqualTo("european central bank");
        assertThat(reloaded.getReviewed()).isFalse();
    }
}
