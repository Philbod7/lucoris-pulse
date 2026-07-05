package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Person (Entitäts-Schicht C). Wie {@link Organization}: freier NER-Text ohne Identifikator,
 * daher Surrogat aus {@code person_seq} plus Alias-Resolver ({@link PersonAlias}).
 * Namensgleichheit ist hier gefährlicher — im Zweifel konservativ nicht mergen.
 */
@Entity
@Table(name = "person")
public class Person {

    /** Surrogat aus {@code person_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "person_generator")
    @SequenceGenerator(name = "person_generator", sequenceName = "person_seq", allocationSize = 50)
    @Column(name = "person_id")
    private Long personId;

    /** Gewählter Anzeigename der Person. */
    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    /** Normalisierter Primärname (eindeutig). */
    @Column(name = "person_norm", nullable = false, unique = true)
    private String personNorm;

    /** Ob die Auflösung manuell bestätigt wurde. */
    @Column(name = "is_reviewed", nullable = false)
    private Boolean reviewed = false;

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String getPersonNorm() {
        return personNorm;
    }

    public void setPersonNorm(String personNorm) {
        this.personNorm = personNorm;
    }

    public Boolean getReviewed() {
        return reviewed;
    }

    public void setReviewed(Boolean reviewed) {
        this.reviewed = reviewed;
    }
}
