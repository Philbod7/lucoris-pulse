package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.ArticleLocationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Aufgelöste Verknüpfung Artikel↔Ort (Entitäts-Schicht C). Composite-FK auf {@link Article}
 * ({@code article_id}, {@code seen_date}) plus FK auf den aufgelösten {@link Location}.
 *
 * @see ArticleLocationId
 */
@Entity
@Table(name = "article_location")
@IdClass(ArticleLocationId.class)
public class ArticleLocation {

    /** Verweis auf den Artikel. */
    @Id
    @Column(name = "article_id")
    private Long articleId;

    /** Erfassungszeitpunkt (denormalisiert für Zeitfilter). */
    @Id
    @Column(name = "seen_date")
    private Instant seenDate;

    /** FK auf den aufgelösten Ort. */
    @Id
    @Column(name = "location_id")
    private Long locationId;

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

    public Long getLocationId() {
        return locationId;
    }

    public void setLocationId(Long locationId) {
        this.locationId = locationId;
    }
}
