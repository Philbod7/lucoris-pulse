package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Thema (Entitäts-Schicht C). Der GKG-Themencode aus GDELTs kontrollierter Taxonomie ist bereits
 * kanonisch und daher selbst der Primärschlüssel — kein Resolver nötig. Zugleich die redaktionelle
 * Kontrolle darüber, was als marktrelevant zählt.
 */
@Entity
@Table(name = "theme")
public class Theme {

    /** GKG-Themencode aus GDELTs kontrollierter Taxonomie (bereits kanonisch). */
    @Id
    @Column(name = "theme_code")
    private String themeCode;

    /** Fachliche Kategorie: {@code 'economics'} | {@code 'politics'} | {@code 'geopolitics'} | ... */
    @Column(name = "category")
    private String category;

    /** Marktbewegend-Flag (Zinsen, Wahlen, Energie, Regulierung ...). */
    @Column(name = "is_market_relevant", nullable = false)
    private Boolean marketRelevant = false;

    /** Optionale Klartextbezeichnung des Themas. */
    @Column(name = "label")
    private String label;

    public String getThemeCode() {
        return themeCode;
    }

    public void setThemeCode(String themeCode) {
        this.themeCode = themeCode;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getMarketRelevant() {
        return marketRelevant;
    }

    public void setMarketRelevant(Boolean marketRelevant) {
        this.marketRelevant = marketRelevant;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
