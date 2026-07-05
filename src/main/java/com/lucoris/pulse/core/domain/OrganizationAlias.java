package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Resolver-Eintrag für Organisationen: bildet eine normalisierte Schreibvariante auf eine
 * {@link Organization} ab. Der Ingest matcht rohe NER-Texte (über {@code norm_name}) gegen
 * {@code alias_norm}; bei Treffer wird die zugehörige {@code organization_id} verwendet.
 */
@Entity
@Table(name = "organization_alias")
public class OrganizationAlias {

    /** Normalisierte Schreibweise (Lookup-Ziel des Ingest). */
    @Id
    @Column(name = "alias_norm")
    private String aliasNorm;

    /** Zugehörige Organisation. */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** Beispiel-Rohschreibweise aus GDELT. */
    @Column(name = "raw_example")
    private String rawExample;

    /** Art des Alias: {@code 'legal'} | {@code 'brand'} | {@code 'short'} | ... */
    @Column(name = "alias_type")
    private String aliasType;

    /** Flag für kollisionsgefährdete Allerweltsnamen. */
    @Column(name = "is_ambiguous", nullable = false)
    private Boolean ambiguous = false;

    public String getAliasNorm() {
        return aliasNorm;
    }

    public void setAliasNorm(String aliasNorm) {
        this.aliasNorm = aliasNorm;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getRawExample() {
        return rawExample;
    }

    public void setRawExample(String rawExample) {
        this.rawExample = rawExample;
    }

    public String getAliasType() {
        return aliasType;
    }

    public void setAliasType(String aliasType) {
        this.aliasType = aliasType;
    }

    public Boolean getAmbiguous() {
        return ambiguous;
    }

    public void setAmbiguous(Boolean ambiguous) {
        this.ambiguous = ambiguous;
    }
}
