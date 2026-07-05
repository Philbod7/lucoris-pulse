package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * Company (Auflösungs-Schicht D). Nicht jede {@link Organization} ist ein handelbares
 * Wertpapier (Behörden, NGOs, Notenbanken); jede getrackte Company verweist aber auf genau
 * eine Organisationsentität. Surrogat aus {@code company_seq}.
 */
@Entity
@Table(name = "company")
public class Company {

    /** Surrogat aus {@code company_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "company_generator")
    @SequenceGenerator(name = "company_generator", sequenceName = "company_seq", allocationSize = 50)
    @Column(name = "company_id")
    private Long companyId;

    /** Verknüpfung zur Organisationsentität (1:1, eindeutig). */
    @Column(name = "organization_id", nullable = false, unique = true)
    private Long organizationId;

    /** Offizieller Anzeigename des Unternehmens. */
    @Column(name = "primary_name", nullable = false)
    private String primaryName;

    /** Börsenkürzel (z.B. AAPL), optional. */
    @Column(name = "ticker")
    private String ticker;

    /** ISIN-Wertpapierkennung, optional. */
    @Column(name = "isin")
    private String isin;

    /** Ob das Unternehmen aktiv getrackt wird. */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getPrimaryName() {
        return primaryName;
    }

    public void setPrimaryName(String primaryName) {
        this.primaryName = primaryName;
    }

    public String getTicker() {
        return ticker;
    }

    public void setTicker(String ticker) {
        this.ticker = ticker;
    }

    public String getIsin() {
        return isin;
    }

    public void setIsin(String isin) {
        this.isin = isin;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
