package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.ArticlePersonId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Aufgelöste Verknüpfung Artikel↔Person (Entitäts-Schicht C). Composite-FK auf {@link Article}
 * ({@code article_id}, {@code seen_date}) plus FK auf die aufgelöste {@link Person}.
 *
 * @see ArticlePersonId
 */
@Entity
@Table(name = "article_person")
@IdClass(ArticlePersonId.class)
public class ArticlePerson {

    /** Verweis auf den Artikel. */
    @Id
    @Column(name = "article_id")
    private Long articleId;

    /** Erfassungszeitpunkt (denormalisiert für Zeitfilter). */
    @Id
    @Column(name = "seen_date")
    private Instant seenDate;

    /** FK auf die aufgelöste Person. */
    @Id
    @Column(name = "person_id")
    private Long personId;

    /** Rohschreibweise wie im Artikel (Audit). */
    @Column(name = "raw_name")
    private String rawName;

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

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public String getRawName() {
        return rawName;
    }

    public void setRawName(String rawName) {
        this.rawName = rawName;
    }
}
