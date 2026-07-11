package com.lucoris.pulse.ingest.gdelt;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extrahiert die distinkten Themen-Codes aus einer GKG-V2EnhancedThemes-Liste.
 * Format: {@code CODE,offset;CODE,offset;...} — je Segment wird der Code vor dem ersten Komma
 * genommen. Reines POJO, von {@link MarketRelevanceFilter} und der Themen-Statistik genutzt.
 */
public final class GdeltThemes {

    private GdeltThemes() {
    }

    /** @return distinkte Themen-Codes eines Artikels (leere Menge bei null/leer). */
    public static Set<String> codes(String v2Themes) {
        if (v2Themes == null || v2Themes.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String segment : v2Themes.split(";")) {
            if (segment.isEmpty()) {
                continue;
            }
            int comma = segment.indexOf(',');
            String code = (comma >= 0 ? segment.substring(0, comma) : segment).trim();
            if (!code.isEmpty()) {
                out.add(code);
            }
        }
        return out;
    }
}
