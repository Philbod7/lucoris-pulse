package com.lucoris.pulse.ingest.gdelt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregiert über einen Ingest-Lauf, wie viele GKG-Artikel jeden Themen-Code enthalten (distinkt
 * je Artikel). Dient als Diagnose-Statistik, um zu sehen, welche Themen insgesamt hereinkommen
 * und welche Codes ggf. ins Marktrelevanz-Set aufgenommen werden sollten. Reines POJO.
 */
public final class ThemeHistogram {

    private final Map<String, Long> articlesPerCode = new HashMap<>();
    private long articles;

    /** Zählt einen Artikel und seine (distinkten) Themen-Codes. */
    public void addArticle(Collection<String> codes) {
        articles++;
        for (String code : codes) {
            articlesPerCode.merge(code, 1L, Long::sum);
        }
    }

    /** Zahl der gezählten Artikel. */
    public long articles() {
        return articles;
    }

    /** Zahl distinkter Themen-Codes. */
    public int distinctCodes() {
        return articlesPerCode.size();
    }

    /** Artikelzahl für einen Code (0, falls nicht vorgekommen). */
    public long articlesWith(String code) {
        return articlesPerCode.getOrDefault(code, 0L);
    }

    /** Codes absteigend nach Artikelzahl, bei Gleichstand alphabetisch. */
    public List<Map.Entry<String, Long>> sortedByCountDesc() {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(articlesPerCode.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));
        return entries;
    }
}
