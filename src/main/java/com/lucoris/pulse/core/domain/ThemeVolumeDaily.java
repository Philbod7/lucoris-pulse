package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.ThemeVolumeDailyId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Tages-Baseline je Thema (Signal-Schicht E). Liefert das Tagesvolumen als Referenz für
 * Spike-Erkennung ({@code spike_ratio} in {@link EventSignificance}). Zusammengesetzter Schlüssel
 * ({@code theme_code}, {@code day}).
 *
 * @see ThemeVolumeDailyId
 */
@Entity
@Table(name = "theme_volume_daily")
@IdClass(ThemeVolumeDailyId.class)
public class ThemeVolumeDaily {

    /** GKG-Themencode. */
    @Id
    @Column(name = "theme_code")
    private String themeCode;

    /** Tag der Aggregation. */
    @Id
    @Column(name = "day")
    private LocalDate day;

    /** Tagesvolumen je Thema (Baseline für Spikes). */
    @Column(name = "article_count", nullable = false)
    private Integer articleCount = 0;

    public String getThemeCode() {
        return themeCode;
    }

    public void setThemeCode(String themeCode) {
        this.themeCode = themeCode;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public Integer getArticleCount() {
        return articleCount;
    }

    public void setArticleCount(Integer articleCount) {
        this.articleCount = articleCount;
    }
}
