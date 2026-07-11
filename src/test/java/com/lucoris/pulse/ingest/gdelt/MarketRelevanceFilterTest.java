package com.lucoris.pulse.ingest.gdelt;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.core.domain.GdeltGkg;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests des Marktrelevanz-Filters (reines POJO, kein Spring/Netz). Sichert die Kern-Regel:
 * behalten nur bei nicht-leerem Präfix-Schnitt der GKG-Themen mit dem Marktrelevanz-Set.
 */
class MarketRelevanceFilterTest {

    private final MarketRelevanceFilter filter = new MarketRelevanceFilter(List.of("ECON_", "EPU_"));

    @Test
    void keepsArticleWithEconTheme() {
        assertThat(filter.matches("TAX_FNCACT,10;ECON_STOCKMARKET,55")).isTrue();
    }

    @Test
    void keepsArticleWithEpuTheme() {
        assertThat(filter.matches("EPU_POLICY_GOVERNMENT,3;TAX_FNCACT,10")).isTrue();
    }

    @Test
    void dropsArticleWithoutMarketTheme() {
        assertThat(filter.matches("TAX_FNCACT,10;WB_123_AGRICULTURE,20;CRISISLEX_C03,5")).isFalse();
    }

    @Test
    void dropsArticleWithoutThemes() {
        assertThat(filter.matches(null)).isFalse();
        assertThat(filter.matches("")).isFalse();
    }

    @Test
    void isRelevantReadsGkgV2Themes() {
        GdeltGkg relevant = new GdeltGkg();
        relevant.setV2Themes("ECON_INFLATION,12");
        assertThat(filter.isRelevant(relevant)).isTrue();

        GdeltGkg irrelevant = new GdeltGkg();
        irrelevant.setV2Themes("SOC_POINTSOFINTEREST,1");
        assertThat(filter.isRelevant(irrelevant)).isFalse();
    }

    @Test
    void emptyPrefixSetDropsEverything() {
        MarketRelevanceFilter none = new MarketRelevanceFilter(List.of());
        assertThat(none.matches("ECON_STOCKMARKET,1")).isFalse();
    }
}
