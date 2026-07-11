package com.lucoris.pulse.ingest.gdelt;

import com.lucoris.pulse.core.domain.GdeltGkg;
import java.util.List;

/**
 * Marktrelevanz-Filter (reines POJO). Entscheidet VOR dem Speichern, ob ein GKG-Artikel behalten
 * wird: relevant ist er, wenn mindestens eine seiner GKG-Themen mit einem der konfigurierten
 * Marktrelevanz-Präfixe beginnt (z.B. {@code ECON_}, {@code EPU_}) — ein nicht-leerer
 * Mengen-Schnitt zwischen Artikel-Themen und Marktrelevanz-Set. Artikel ohne Themen oder ohne
 * Treffer werden verworfen, bevor irgendeine Entität geschrieben wird.
 *
 * <p>Themenformat (V2EnhancedThemes): {@code CODE,offset;CODE,offset;...} — je Segment wird der
 * Code vor dem ersten Komma geprüft. Ein Config-Eintrag wirkt als Präfix ({@code ECON_} deckt die
 * ganze Familie ab; ein vollständiger Code matcht exakt).
 */
public final class MarketRelevanceFilter {

    private final List<String> relevantPrefixes;

    public MarketRelevanceFilter(List<String> relevantPrefixes) {
        this.relevantPrefixes = relevantPrefixes == null ? List.of() : List.copyOf(relevantPrefixes);
    }

    /** @return {@code true}, wenn der Artikel marktrelevant ist (Theme trifft ein Präfix). */
    public boolean isRelevant(GdeltGkg gkg) {
        return matches(gkg.getV2Themes());
    }

    /** Prüft die semikolon-getrennte V2-Enhanced-Themenliste gegen das Marktrelevanz-Set. */
    boolean matches(String v2Themes) {
        if (relevantPrefixes.isEmpty()) {
            return false;
        }
        for (String code : GdeltThemes.codes(v2Themes)) {
            if (isRelevantCode(code)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true}, wenn ein einzelner Themen-Code mit einem Marktrelevanz-Präfix beginnt. */
    public boolean isRelevantCode(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        for (String prefix : relevantPrefixes) {
            if (!prefix.isEmpty() && code.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
