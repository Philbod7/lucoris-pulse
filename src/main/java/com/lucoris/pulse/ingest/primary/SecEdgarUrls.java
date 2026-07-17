package com.lucoris.pulse.ingest.primary;

/**
 * Die Identität und die URL-Arithmetik von EDGAR — gemeinsam für beide EDGAR-Handler
 * ({@link SecEdgarAdapter} über die submissions-API, {@link SecEdgarDailyIndexAdapter} über den
 * Tagesindex). Beide beschreiben dieselben Einreichungen und müssen sie als DIESELBE Meldung
 * erkennen.
 *
 * <p><b>Die Identität ist die Accession-Nummer, nicht der Permalink.</b> Das ist keine Feinheit,
 * sondern nachgemessen: ein Filing mit Mit-Anmeldern ist unter JEDER beteiligten CIK erreichbar —
 * {@code 0000100517-26-000135} etwa unter {@code 100517} (United Airlines Holdings) und unter
 * {@code 319687} (United Airlines, Inc.); beide URLs liefern dasselbe Dokument, und beide CIKs
 * führen die Accession in ihrer submissions-Antwort. Der Permalink ist also eine Darstellungsform,
 * kein Schlüssel. Wer über ihn dedupliziert, speichert solche Filings doppelt (am 2026-07-15: 2 von
 * 173 8-K, über alle Formulartypen 788 von 2392).
 *
 * <p>Pures POJO ohne Abhängigkeiten.
 */
final class SecEdgarUrls {

    /** {@code https://www.sec.gov/Archives/edgar/data/{cik}/{accessionOhneStriche}/{accession}-index.htm} */
    private static final String ARCHIVES_BASE = "https://www.sec.gov/Archives/edgar/data/";

    /**
     * Namensraum des Dedup-Schlüssels. KONSTANT und bewusst NICHT die {@code sourceId}: Echtzeit-Pfad
     * und Tagesindex sind zwei Quellen, die dieselbe Einreichung liefern — nur mit demselben Präfix
     * erkennen sie sie als dieselbe Meldung. Der Namensraum verhindert zugleich, dass eine fremde
     * Quelle mit derselben Zeichenkette kollidiert.
     */
    private static final String DEDUP_NAMESPACE = "sec-edgar:accession:";

    private SecEdgarUrls() {}

    /**
     * Der quellenübergreifende Dedup-Schlüssel einer Einreichung — die Accession-Nummer, nicht der
     * Permalink (siehe Klassendoc). Wandert als {@link FeedItem#dedupKey()} in die Meldung und hat
     * damit in {@link DedupKeys} Vorrang.
     *
     * @param accession Accession-Nummer mit Strichen, z.B. {@code 0000100517-26-000135}
     */
    static String dedupKey(String accession) {
        return DEDUP_NAMESPACE + accession.trim();
    }

    /**
     * Der Deep-Link auf EINE Einreichung — für die Anzeige (Institution + Datum + Deep-Link), NICHT
     * für die Identität: die liefert {@link #dedupKey(String)}.
     *
     * <p>Bewusst konstruiert statt übernommen: die Links, die EDGAR selbst in Feeds mitgibt, zeigen
     * auf die firmenweite Übersicht — sie würden alle Filings einer Firma auf eine URL werfen. Der
     * konstruierte Pfad liegt zudem unter {@code /Archives/edgar/data}, das die robots.txt der SEC
     * ausdrücklich erlaubt.
     *
     * <p>Bei Mit-Anmeldern gibt es MEHRERE gültige Ergebnisse (je CIK eines), die alle dasselbe
     * Dokument liefern — welches gespeichert wird, entscheidet schlicht, wer zuerst da war. Das ist
     * unschädlich, solange nicht darüber dedupliziert wird.
     *
     * @param cik       CIK, mit oder ohne führende Nullen (EDGAR erwartet sie im Pfad OHNE)
     * @param accession Accession-Nummer mit Strichen, z.B. {@code 0000320193-26-000101}
     */
    static String filingPermalink(String cik, String accession) {
        long cikNumber = Long.parseLong(cik.trim()); // Aufrufer liefern ausschließlich Ziffern
        String accessionDigits = accession.replace("-", "");
        return ARCHIVES_BASE + cikNumber + "/" + accessionDigits + "/" + accession + "-index.htm";
    }

    /**
     * Die Accession-Nummer aus einem EDGAR-Archivpfad, z.B.
     * {@code edgar/data/1000275/0000950103-26-010623.txt} -> {@code 0000950103-26-010623}.
     *
     * @return die Accession-Nummer, oder {@code null}, wenn der Pfad keine trägt
     */
    static String accessionFromArchivePath(String path) {
        if (path == null) {
            return null;
        }
        String file = path.substring(path.lastIndexOf('/') + 1).trim();
        int dot = file.lastIndexOf('.');
        String bare = dot < 0 ? file : file.substring(0, dot);
        return isAccession(bare) ? bare : null;
    }

    /** Form einer Accession-Nummer: {@code 10 Ziffern - 2 Ziffern - 6 Ziffern}. */
    static boolean isAccession(String value) {
        if (value == null || value.length() != 20) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean strichErwartet = i == 10 || i == 13;
            if (strichErwartet ? c != '-' : c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
