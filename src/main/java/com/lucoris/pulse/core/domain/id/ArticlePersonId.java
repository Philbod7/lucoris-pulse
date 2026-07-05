package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der Link-Tabelle {@code article_person}: Composite-FK auf den
 * Artikel ({@code article_id}, {@code seen_date}) plus aufgelöster {@code person_id}.
 */
public class ArticlePersonId implements Serializable {

    private Long articleId;
    private Instant seenDate;
    private Long personId;

    public ArticlePersonId() {
    }

    public ArticlePersonId(Long articleId, Instant seenDate, Long personId) {
        this.articleId = articleId;
        this.seenDate = seenDate;
        this.personId = personId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArticlePersonId that)) {
            return false;
        }
        return Objects.equals(articleId, that.articleId)
                && Objects.equals(seenDate, that.seenDate)
                && Objects.equals(personId, that.personId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(articleId, seenDate, personId);
    }
}
