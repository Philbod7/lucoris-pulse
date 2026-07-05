package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der partitionierten Tabelle {@code article}: Surrogat
 * {@code article_id} (aus {@code article_seq}) plus Partitionsschlüssel {@code seen_date}.
 * Die Link-Tabellen referenzieren genau dieses Paar als Composite-FK.
 */
public class ArticleId implements Serializable {

    private Long articleId;
    private Instant seenDate;

    public ArticleId() {
    }

    public ArticleId(Long articleId, Instant seenDate) {
        this.articleId = articleId;
        this.seenDate = seenDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArticleId that)) {
            return false;
        }
        return Objects.equals(articleId, that.articleId)
                && Objects.equals(seenDate, that.seenDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(articleId, seenDate);
    }
}
