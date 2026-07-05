package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.ArticleThemeId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Aufgelöste Verknüpfung Artikel↔Thema (Entitäts-Schicht C). Composite-FK auf {@link Article}
 * ({@code article_id}, {@code seen_date}); {@code seen_date} ist für Zeitfilter denormalisiert.
 * Skalar-Mapping (keine Navigation) — genügt für den Firehose und die FK-basierte Suche.
 *
 * @see ArticleThemeId
 */
@Entity
@Table(name = "article_theme")
@IdClass(ArticleThemeId.class)
public class ArticleTheme {

    /** Verweis auf den Artikel. */
    @Id
    @Column(name = "article_id")
    private Long articleId;

    /** Erfassungszeitpunkt (denormalisiert für Zeitfilter). */
    @Id
    @Column(name = "seen_date")
    private Instant seenDate;

    /** FK auf das kanonische Thema. */
    @Id
    @Column(name = "theme_code")
    private String themeCode;

    /** Optionale Gewichtung/Häufigkeit des Themas im Artikel. */
    @Column(name = "salience")
    private Float salience;

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

    public String getThemeCode() {
        return themeCode;
    }

    public void setThemeCode(String themeCode) {
        this.themeCode = themeCode;
    }

    public Float getSalience() {
        return salience;
    }

    public void setSalience(Float salience) {
        this.salience = salience;
    }
}
