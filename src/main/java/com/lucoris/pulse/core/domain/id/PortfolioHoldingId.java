package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der Link-Tabelle {@code portfolio_holding}: Depot-Position als
 * Paar ({@code portfolio_id}, {@code company_id}).
 */
public class PortfolioHoldingId implements Serializable {

    private Long portfolioId;
    private Long companyId;

    public PortfolioHoldingId() {
    }

    public PortfolioHoldingId(Long portfolioId, Long companyId) {
        this.portfolioId = portfolioId;
        this.companyId = companyId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PortfolioHoldingId that)) {
            return false;
        }
        return Objects.equals(portfolioId, that.portfolioId)
                && Objects.equals(companyId, that.companyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(portfolioId, companyId);
    }
}
