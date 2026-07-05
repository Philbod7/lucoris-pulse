package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der Link-Tabelle {@code article_location}: Composite-FK auf den
 * Artikel ({@code article_id}, {@code seen_date}) plus aufgelöster {@code location_id}.
 */
public class ArticleLocationId implements Serializable {

    private Long articleId;
    private Instant seenDate;
    private Long locationId;

    public ArticleLocationId() {
    }

    public ArticleLocationId(Long articleId, Instant seenDate, Long locationId) {
        this.articleId = articleId;
        this.seenDate = seenDate;
        this.locationId = locationId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArticleLocationId that)) {
            return false;
        }
        return Objects.equals(articleId, that.articleId)
                && Objects.equals(seenDate, that.seenDate)
                && Objects.equals(locationId, that.locationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(articleId, seenDate, locationId);
    }
}
