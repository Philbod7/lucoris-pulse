package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Portfolio (Auflösungs-Schicht D). Depot eines Nutzers; die enthaltenen Positionen stehen in
 * {@link PortfolioHolding}. Surrogat aus {@code portfolio_seq}.
 */
@Entity
@Table(name = "portfolio")
public class Portfolio {

    /** Surrogat aus {@code portfolio_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "portfolio_generator")
    @SequenceGenerator(name = "portfolio_generator", sequenceName = "portfolio_seq", allocationSize = 50)
    @Column(name = "portfolio_id")
    private Long portfolioId;

    /** Supabase-Auth-Nutzer (pseudonyme UUID). */
    @Column(name = "owner_uuid", nullable = false)
    private UUID ownerUuid;

    public Long getPortfolioId() {
        return portfolioId;
    }

    public void setPortfolioId(Long portfolioId) {
        this.portfolioId = portfolioId;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }
}
