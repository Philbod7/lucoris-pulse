package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.PortfolioHoldingId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Depot-Position (Auflösungs-Schicht D): verbindet ein {@link Portfolio} mit einer enthaltenen
 * {@link Company}. Reiner zusammengesetzter Schlüssel ohne weitere Spalten.
 *
 * @see PortfolioHoldingId
 */
@Entity
@Table(name = "portfolio_holding")
@IdClass(PortfolioHoldingId.class)
public class PortfolioHolding {

    /** Zugehöriges Portfolio. */
    @Id
    @Column(name = "portfolio_id")
    private Long portfolioId;

    /** Enthaltenes Unternehmen (Depot-Position). */
    @Id
    @Column(name = "company_id")
    private Long companyId;

    public Long getPortfolioId() {
        return portfolioId;
    }

    public void setPortfolioId(Long portfolioId) {
        this.portfolioId = portfolioId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}
