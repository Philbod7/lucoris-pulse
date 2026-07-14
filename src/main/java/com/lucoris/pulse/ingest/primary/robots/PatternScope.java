package com.lucoris.pulse.ingest.primary.robots;

import java.util.List;
import java.util.Locale;

/**
 * Bedingung (c) der Einladungs-Regel: Erfasst ein robots-Disallow den Feed nur als NEBENWIRKUNG —
 * oder meint es ihn?
 *
 * <p>Nur ein beiläufiges Muster kann von einer ausdrücklichen Einladung des Herausgebers aufgewogen
 * werden. Beispiel bundesfinanzministerium.de: das Muster sperrt den ganzen CMS-Zweig
 * {@code SiteGlobals} (dort liegen Formulare, Skripte, Stylesheets) — dass der RSS-Feed zufällig
 * auch dort liegt, ist Kollateralschaden. Nennt ein Muster dagegen {@code rss}, {@code feed} oder
 * {@code atom}, oder zielt es auf eine konkrete Datei, dann ist der Feed gemeint und es bleibt
 * verboten.
 *
 * <p><strong>Die Klassifikation ist absichtlich überstreng.</strong> Ein fälschlich als „gezielt"
 * eingestuftes Muster führt zu einem verweigerten Abruf — ärgerlich, aber harmlos. Ein fälschlich
 * als „beiläufig" eingestuftes führt zu einem Abruf gegen den erkennbaren Willen des Herausgebers.
 * Im Zweifel also: nicht beiläufig.
 *
 * <p>Reines POJO — kein Netz, kein Spring, tabellengetrieben testbar.
 */
public final class PatternScope {

    /**
     * Feed-Vokabeln. Nennt ein Muster eine davon, meint es den Feed — nicht bloß nebenbei. Bewusst
     * Substring-Vergleich statt Wortgrenzen: {@code RSSFeed}, {@code newsfeed}, {@code feeds} und
     * {@code /xml/} fallen so alle in die strenge Richtung.
     */
    private static final List<String> FEED_TOKENS = List.of("rss", "feed", "atom", "xml");

    private PatternScope() {}

    /**
     * Erfasst {@code pattern} den Feed unter {@code feedPath} nur beiläufig?
     *
     * @param pattern  das Disallow-Muster aus der robots.txt, unverändert
     * @param feedPath der Pfad der Feed-URL (Query wird ignoriert)
     */
    public static boolean isIncidental(String pattern, String feedPath) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String muster = pattern.trim().toLowerCase(Locale.ROOT);

        // Ein End-Anker zeigt auf eine konkrete Ressource, nicht auf einen Zweig.
        if (muster.endsWith("$")) {
            return false;
        }
        // Das Muster nennt den Feed beim Namen.
        if (FEED_TOKENS.stream().anyMatch(muster::contains)) {
            return false;
        }
        // Das Muster endet auf einer Datei (…/irgendwas.ext) -> Endpunkt, kein Zweig.
        if (hasFileExtension(lastSegment(muster))) {
            return false;
        }

        int imMuster = literalSegments(muster);
        int imPfad = literalSegments(stripQuery(feedPath).toLowerCase(Locale.ROOT));

        // Mindestens ein Segment Substanz: ein Total-Bann ("/", "*", "/*") hat null Segmente und ist
        // NIE beiläufig — er sperrt bewusst alles, nicht den Feed nebenbei.
        // Und mindestens ein Pfadsegment des Feeds bleibt vom Muster ungenannt: dann ist es eine
        // Zweig-Regel, keine Endpunkt-Regel.
        return imMuster >= 1 && imMuster < imPfad;
    }

    /**
     * Segmente mit Inhalt. Reine Wildcard-Segmente zählen nicht: ein Muster aus Sternchen und einem
     * Namen hat ein Segment, nicht zwei. Ein Total-Bann hat null.
     */
    private static int literalSegments(String path) {
        int count = 0;
        for (String segment : path.split("/")) {
            String rest = segment.replace("*", "").trim();
            if (!rest.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** Sieht das letzte Segment aus wie eine Datei? {@code bericht.pdf} ja, {@code v2.1} auch — streng. */
    private static boolean hasFileExtension(String segment) {
        return segment.matches(".*\\.[a-z0-9]{1,5}$");
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }
}
