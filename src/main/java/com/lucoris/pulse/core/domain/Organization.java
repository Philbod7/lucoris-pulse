package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Organisation (Entitäts-Schicht C). GDELT liefert für Organisationen KEINEN Identifikator,
 * nur rohen NER-Text — daher ein surrogater Schlüssel aus {@code organization_seq} plus ein
 * Alias-Resolver ({@link OrganizationAlias}). Der Kernfall der Auflösung.
 */
@Entity
@Table(name = "organization")
public class Organization {

    /** Surrogat aus {@code organization_seq} (GDELT liefert keinen; Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "organization_generator")
    @SequenceGenerator(name = "organization_generator", sequenceName = "organization_seq", allocationSize = 50)
    @Column(name = "organization_id")
    private Long organizationId;

    /** Gewählter Anzeigename der Entität. */
    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    /** Normalisierter Primärname (eindeutig). */
    @Column(name = "org_norm", nullable = false, unique = true)
    private String orgNorm;

    /** Erste Sichtung dieser Organisation. */
    @Column(name = "first_seen")
    private Instant firstSeen;

    /** Ob die Auflösung manuell bestätigt wurde. */
    @Column(name = "is_reviewed", nullable = false)
    private Boolean reviewed = false;

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String getOrgNorm() {
        return orgNorm;
    }

    public void setOrgNorm(String orgNorm) {
        this.orgNorm = orgNorm;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Boolean getReviewed() {
        return reviewed;
    }

    public void setReviewed(Boolean reviewed) {
        this.reviewed = reviewed;
    }
}
