package com.lucoris.pulse.ingest.primary;

/**
 * Die URL-Arithmetik von EDGAR — gemeinsam für beide EDGAR-Handler ({@link SecEdgarAdapter} über die
 * submissions-API, {@link SecEdgarDailyIndexAdapter} über den Tagesindex). Beide beschreiben
 * dieselben Einreichungen und MÜSSEN denselben Permalink erzeugen: nur dann fallen die Meldungen in
 * {@link DedupKeys} auf denselben {@code dedup_key} und werden nicht doppelt gespeichert.
 *
 * <p>Pures POJO ohne Abhängigkeiten.
 */
final class SecEdgarUrls {

    /** {@code https://www.sec.gov/Archives/edgar/data/{cik}/{accessionOhneStriche}/{accession}-index.htm} */
    private static final String ARCHIVES_BASE = "https://www.sec.gov/Archives/edgar/data/";

    private SecEdgarUrls() {}

    /**
     * Der stabile Deep-Link auf EINE Einreichung.
     *
     * <p>Bewusst konstruiert statt übernommen: die Links, die EDGAR selbst in Feeds mitgibt, zeigen
     * auf die firmenweite Übersicht — als Dedup-Grundlage würden sie alle Filings einer Firma zu
     * einer Meldung kollabieren. Der Pfad liegt zudem unter {@code /Archives/edgar/data}, das die
     * robots.txt der SEC ausdrücklich erlaubt.
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
