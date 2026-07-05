package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der Link-Tabelle {@code article_theme}: Composite-FK auf den
 * Artikel ({@code article_id}, {@code seen_date}) plus kanonischer {@code theme_code}.
 */
public class ArticleThemeId implements Serializable {

    private Long articleId;
    private Instant seenDate;
    private String themeCode;

    public ArticleThemeId() {
    }

    public ArticleThemeId(Long articleId, Instant seenDate, String themeCode) {
        this.articleId = articleId;
        this.seenDate = seenDate;
        this.themeCode = themeCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ArticleThemeId that)) {
            return false;
        }
        return Objects.equals(articleId, that.articleId)
                && Objects.equals(seenDate, that.seenDate)
                && Objects.equals(themeCode, that.themeCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(articleId, seenDate, themeCode);
    }
}
