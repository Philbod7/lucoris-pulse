package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der Link-Tabelle {@code article_organization}: Composite-FK auf
 * den Artikel ({@code article_id}, {@code seen_date}) plus aufgelöster {@code organization_id}.
 */
public class ArticleOrganizationId implements Serializable {

    private Long articleId;
    private Instant seenDate;
    private Long organizationId;

    public ArticleOrganizationId() {
    }

    public ArticleOrganizationId(Long articleId, Instant seenDate, Long organizationId) {
        this.articleId = articleId;
        this.seenDate = seenDate;
        this.organizationId = organizationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArticleOrganizationId that)) {
            return false;
        }
        return Objects.equals(articleId, that.articleId)
                && Objects.equals(seenDate, that.seenDate)
                && Objects.equals(organizationId, that.organizationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(articleId, seenDate, organizationId);
    }
}
