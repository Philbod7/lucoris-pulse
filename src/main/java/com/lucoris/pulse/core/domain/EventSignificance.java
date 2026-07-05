package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Signifikanz eines Ereignisses (Signal-Schicht E). Der Ingest-Job füllt die Metrik-Spalten;
 * {@code significance_score} ist eine von der DB berechnete {@code GENERATED ALWAYS ... STORED}
 * Spalte (gewichtete Mischung aus Coverage-Menge, Domain-/Länder-Diversität, Spike und
 * Goldstein-Intensität) und wird ausschließlich LESEND gemappt. PK ist die
 * {@code global_event_id} (FK auf {@link GdeltEvent}).
 */
@Entity
@Table(name = "event_significance")
public class EventSignificance {

    /** Verweis auf das Ereignis. */
    @Id
    @Column(name = "global_event_id")
    private Long globalEventId;

    /** Erste Sichtung des Ereignisses. */
    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;

    /** Letzte Sichtung (Basis für 24h-Fenster). */
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    /** Anzahl abdeckender Artikel. */
    @Column(name = "num_articles", nullable = false)
    private Integer numArticles = 0;

    /** Anzahl unterschiedlicher Quell-Domains (Breite). */
    @Column(name = "distinct_domains", nullable = false)
    private Integer distinctDomains = 0;

    /** Anzahl unterschiedlicher Länder (Streuung). */
    @Column(name = "distinct_countries", nullable = false)
    private Integer distinctCountries = 0;

    /** Durchschnittlicher Tonwert. */
    @Column(name = "avg_tone")
    private BigDecimal avgTone;

    /** Goldstein-Wert des Ereignisses. */
    @Column(name = "goldstein")
    private BigDecimal goldstein;

    /** Verhältnis aktuelles Volumen zu Baseline (vom Job berechnet). */
    @Column(name = "spike_ratio")
    private BigDecimal spikeRatio;

    /** Marktrelevanz-Flag (vom Job aus {@link Theme} abgeleitet). */
    @Column(name = "is_market_relevant", nullable = false)
    private Boolean marketRelevant = false;

    /**
     * Automatisch berechneter Signifikanzwert (Ranking-Grundlage). DB-generierte STORED-Spalte:
     * nur lesend gemappt ({@code insertable=false, updatable=false}); {@link Generated} liest den
     * berechneten Wert nach Insert/Update per SELECT zurück.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "significance_score", insertable = false, updatable = false)
    private BigDecimal significanceScore;

    public Long getGlobalEventId() {
        return globalEventId;
    }

    public void setGlobalEventId(Long globalEventId) {
        this.globalEventId = globalEventId;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public Integer getNumArticles() {
        return numArticles;
    }

    public void setNumArticles(Integer numArticles) {
        this.numArticles = numArticles;
    }

    public Integer getDistinctDomains() {
        return distinctDomains;
    }

    public void setDistinctDomains(Integer distinctDomains) {
        this.distinctDomains = distinctDomains;
    }

    public Integer getDistinctCountries() {
        return distinctCountries;
    }

    public void setDistinctCountries(Integer distinctCountries) {
        this.distinctCountries = distinctCountries;
    }

    public BigDecimal getAvgTone() {
        return avgTone;
    }

    public void setAvgTone(BigDecimal avgTone) {
        this.avgTone = avgTone;
    }

    public BigDecimal getGoldstein() {
        return goldstein;
    }

    public void setGoldstein(BigDecimal goldstein) {
        this.goldstein = goldstein;
    }

    public BigDecimal getSpikeRatio() {
        return spikeRatio;
    }

    public void setSpikeRatio(BigDecimal spikeRatio) {
        this.spikeRatio = spikeRatio;
    }

    public Boolean getMarketRelevant() {
        return marketRelevant;
    }

    public void setMarketRelevant(Boolean marketRelevant) {
        this.marketRelevant = marketRelevant;
    }

    public BigDecimal getSignificanceScore() {
        return significanceScore;
    }
}
