package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.ArticleId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Artikel-Hub (Abfrage-Schicht B), dedupliziert pro URL. Monatlich RANGE-partitioniert über
 * {@code seen_date}; der zusammengesetzte Primärschlüssel ({@code article_id}, {@code seen_date})
 * macht die FKs der Link-Tabellen partitionierungssicher. {@code article_id} stammt aus
 * {@code article_seq}. Gemappt wird nur die partitionierte Elterntabelle, nicht die Monatskinder.
 *
 * @see ArticleId
 */
@Entity
@Table(name = "article")
@IdClass(ArticleId.class)
public class Article {

    /** Surrogat aus {@code article_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "article_generator")
    @SequenceGenerator(name = "article_generator", sequenceName = "article_seq", allocationSize = 50)
    @Column(name = "article_id")
    private Long articleId;

    /** Erfassungszeitpunkt (Partitionsschlüssel, Basis für 24h-Filter). */
    @Id
    @Column(name = "seen_date")
    private Instant seenDate;

    /** Eindeutige Artikel-URL (Deduplizierungsschlüssel). */
    @Column(name = "url", nullable = false)
    private String url;

    /** Quellenname (Domain/Publikation). */
    @Column(name = "source_common_name")
    private String sourceCommonName;

    /** Herkunftsland der Quelle. */
    @Column(name = "source_country")
    private String sourceCountry;

    /** Sprache des Artikels. */
    @Column(name = "language")
    private String language;

    /** Tonwert des Artikels. */
    @Column(name = "tone")
    private BigDecimal tone;

    /** Rückverweis auf den GKG-Rohdatensatz. */
    @Column(name = "gkg_record_id")
    private String gkgRecordId;

    public Long getArticleId() {
        return articleId;
    }

    public void setArticleId(Long articleId) {
        this.articleId = articleId;
    }

    public Instant getSeenDate() {
        return seenDate;
    }

    public void setSeenDate(Instant seenDate) {
        this.seenDate = seenDate;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSourceCommonName() {
        return sourceCommonName;
    }

    public void setSourceCommonName(String sourceCommonName) {
        this.sourceCommonName = sourceCommonName;
    }

    public String getSourceCountry() {
        return sourceCountry;
    }

    public void setSourceCountry(String sourceCountry) {
        this.sourceCountry = sourceCountry;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public BigDecimal getTone() {
        return tone;
    }

    public void setTone(BigDecimal tone) {
        this.tone = tone;
    }

    public String getGkgRecordId() {
        return gkgRecordId;
    }

    public void setGkgRecordId(String gkgRecordId) {
        this.gkgRecordId = gkgRecordId;
    }
}
