package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.ArticleOrganizationId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Aufgelöste Verknüpfung Artikel↔Organisation (Entitäts-Schicht C). Composite-FK auf
 * {@link Article} ({@code article_id}, {@code seen_date}) plus FK auf die aufgelöste
 * {@link Organization}. Trägt die Rohschreibweise fürs Audit.
 *
 * @see ArticleOrganizationId
 */
@Entity
@Table(name = "article_organization")
@IdClass(ArticleOrganizationId.class)
public class ArticleOrganization {

    /** Verweis auf den Artikel. */
    @Id
    @Column(name = "article_id")
    private Long articleId;

    /** Erfassungszeitpunkt (denormalisiert für Zeitfilter). */
    @Id
    @Column(name = "seen_date")
    private Instant seenDate;

    /** FK auf die aufgelöste Organisation. */
    @Id
    @Column(name = "organization_id")
    private Long organizationId;

    /** Rohschreibweise wie im Artikel (Audit/Nachvollziehbarkeit). */
    @Column(name = "raw_name")
    private String rawName;

    /** Optionaler Zeichenoffset im Dokument (Proximity). */
    @Column(name = "char_offset")
    private Integer charOffset;

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

    public Long getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(Long organizationId) {
        this.organizationId = organizationId;
    }

    public String getRawName() {
        return rawName;
    }

    public void setRawName(String rawName) {
        this.rawName = rawName;
    }

    public Integer getCharOffset() {
        return charOffset;
    }

    public void setCharOffset(Integer charOffset) {
        this.charOffset = charOffset;
    }
}
