package com.lucoris.pulse.ingest.primary.robots;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Reiner Unit-Test der Bedingung (c) — ohne Spring, Netz oder DB.
 *
 * <p>Die Grenze verläuft zwischen „das Muster sperrt einen Zweig und erwischt den Feed dabei" und
 * „das Muster meint den Feed". Nur das erste kann eine Einladung aufwiegen.
 */
class PatternScopeTest {

    /** Der echte BMF-Feed-Pfad: 6 Segmente. */
    private static final String BMF_FEED =
            "/SiteGlobals/Functions/RSSFeed/DE/Pressemitteilungen/RSSPressemitteilungen.xml";

    @ParameterizedTest(name = "[{index}] {0} -> beiläufig={1}  ({2})")
    @CsvSource({
        // --- BEILÄUFIG: Zweig-Regeln ohne Feed-Bezug. Nur die können eine Einladung tragen. ---
        "'*/SiteGlobals',                    true,  'der BMF-Realfall: sperrt den CMS-Zweig, Feed liegt zufaellig drin'",
        "'*/SiteGlobals/Functions',          true,  'engerer Zweig, immer noch ohne Feed-Bezug'",
        "'/intern',                          true,  'schlichte Zweig-Regel'",
        "'*/SharedDocs/ExterneLinks',        true,  'zweite BMF-Regel, ebenfalls generisch'",

        // --- GEZIELT: das Muster nennt den Feed beim Namen. ---
        "'*/SiteGlobals/Functions/RSSFeed/', false, 'nennt rss UND feed'",
        "'/feeds',                           false, 'nennt feed'",
        "'/atom',                            false, 'nennt atom'",
        "'/RSS',                             false, 'nennt rss, Grossschreibung egal'",
        "'*/newsfeed/',                      false, 'Substring feed genuegt bewusst'",
        "'/xml',                             false, 'nennt xml'",

        // --- GEZIELT: das Muster zielt auf eine konkrete Ressource. ---
        "'/SiteGlobals/Functions/RSSFeed/DE/Pressemitteilungen/RSSPressemitteilungen.xml', false, 'exakt der Endpunkt'",
        "'/*.pdf$',                          false, 'End-Anker = konkrete Ressource'",
        "'/dokumente/bericht.pdf',           false, 'Dateiendung = Endpunkt, kein Zweig'",

        // --- GEZIELT: Total-Bann. Sperrt bewusst alles, nicht den Feed nebenbei. ---
        "'/',                                false, 'Total-Bann darf NIE durch eine Einladung fallen'",
        "'*',                                false, 'Total-Bann'",
        "'/*',                               false, 'Total-Bann'",
    })
    void classifiesPatternsAgainstTheBmfFeedPath(String muster, boolean beilaeufig, String warum) {
        assertThat(PatternScope.isIncidental(muster, BMF_FEED))
                .as(warum)
                .isEqualTo(beilaeufig);
    }

    @ParameterizedTest(name = "[{index}] Muster {0} vs. Pfad {1} -> beiläufig={2}")
    @CsvSource({
        // Das Muster darf nicht so spezifisch sein wie der Pfad selbst — sonst ist es der Endpunkt.
        "'/a/b/c',   /a/b/c,       false",
        "'/a/b',     /a/b/c,       true",
        "'/a',       /a/b/c,       true",
        // Query zählt nicht mit (sonst verschöbe Destatis' ?nn=241288 die Segmentzahl).
        "'/a',       /a/b?nn=123,  true",
    })
    void patternMustBeBroaderThanTheFeedPathItself(String muster, String pfad, boolean beilaeufig) {
        assertThat(PatternScope.isIncidental(muster, pfad)).isEqualTo(beilaeufig);
    }

    @ParameterizedTest
    @CsvSource({"''", "'   '"})
    void blankPatternIsNeverIncidental(String muster) {
        assertThat(PatternScope.isIncidental(muster, BMF_FEED)).isFalse();
    }
}
