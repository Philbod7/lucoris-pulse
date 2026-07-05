package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Resolver-Eintrag für Personen: bildet eine normalisierte Schreibweise auf eine {@link Person}
 * ab. Analog zu {@link OrganizationAlias}, jedoch konservativer aufzulösen.
 */
@Entity
@Table(name = "person_alias")
public class PersonAlias {

    /** Normalisierte Schreibweise (Lookup-Ziel des Ingest). */
    @Id
    @Column(name = "alias_norm")
    private String aliasNorm;

    /** Zugehörige Person. */
    @Column(name = "person_id", nullable = false)
    private Long personId;

    /** Flag für Namensgleichheit/Mehrdeutigkeit. */
    @Column(name = "is_ambiguous", nullable = false)
    private Boolean ambiguous = false;

    public String getAliasNorm() {
        return aliasNorm;
    }

    public void setAliasNorm(String aliasNorm) {
        this.aliasNorm = aliasNorm;
    }

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public Boolean getAmbiguous() {
        return ambiguous;
    }

    public void setAmbiguous(Boolean ambiguous) {
        this.ambiguous = ambiguous;
    }
}
