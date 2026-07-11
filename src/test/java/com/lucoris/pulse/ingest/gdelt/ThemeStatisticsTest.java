package com.lucoris.pulse.ingest.gdelt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests für die Themen-Statistik: Code-Extraktion ({@link GdeltThemes}) und Aggregation
 * ({@link ThemeHistogram}). Reine POJO-Tests.
 */
class ThemeStatisticsTest {

    @Test
    void extractsDistinctThemeCodes() {
        assertThat(GdeltThemes.codes("ECON_STOCKMARKET,12;ECON_STOCKMARKET,80;TAX_FNCACT,5"))
                .containsExactly("ECON_STOCKMARKET", "TAX_FNCACT");
    }

    @Test
    void extractsNoCodesFromBlank() {
        assertThat(GdeltThemes.codes(null)).isEmpty();
        assertThat(GdeltThemes.codes("")).isEmpty();
    }

    @Test
    void histogramCountsArticlesPerCodeDistinctly() {
        ThemeHistogram h = new ThemeHistogram();
        h.addArticle(GdeltThemes.codes("ECON_A,1;ECON_A,9;TAX_X,3")); // ECON_A einmal je Artikel
        h.addArticle(GdeltThemes.codes("ECON_A,1;EPU_B,2"));
        h.addArticle(GdeltThemes.codes("ECON_A,4"));

        assertThat(h.articles()).isEqualTo(3);
        assertThat(h.distinctCodes()).isEqualTo(3);
        assertThat(h.articlesWith("ECON_A")).isEqualTo(3);
        assertThat(h.articlesWith("TAX_X")).isEqualTo(1);
        assertThat(h.articlesWith("FEHLT")).isZero();
    }

    @Test
    void histogramSortsByCountDescThenAlphabetically() {
        ThemeHistogram h = new ThemeHistogram();
        h.addArticle(GdeltThemes.codes("ECON_A,1;TAX_X,2"));
        h.addArticle(GdeltThemes.codes("ECON_A,1;EPU_B,2"));
        h.addArticle(GdeltThemes.codes("ECON_A,1"));

        List<Map.Entry<String, Long>> sorted = h.sortedByCountDesc();
        assertThat(sorted).extracting(Map.Entry::getKey)
                .containsExactly("ECON_A", "EPU_B", "TAX_X"); // 3, dann 1/1 alphabetisch
        assertThat(sorted.get(0).getValue()).isEqualTo(3L);
    }
}
