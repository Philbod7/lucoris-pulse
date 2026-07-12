package com.lucoris.pulse.ingest.gdelt;

/**
 * Die drei GDELT-V2-Roh-Datensätze eines 15-Minuten-Slices. Kapselt das case-sensitive
 * URL-Suffix (Stolperfalle: {@code .CSV} bei Events/Mentions, {@code .csv} bei GKG — die URLs
 * sind groß-/kleinschreibungssensitiv) sowie den Datensatznamen für das {@code ingest_log}.
 */
public enum GdeltDataset {

    /** Events (Ereignis-Klammer über {@code global_event_id}). */
    EVENTS(".export.CSV.zip", "events"),
    /** Mentions (Erwähnungen je Ereignis). */
    MENTIONS(".mentions.CSV.zip", "mentions"),
    /** GKG (artikelzentriert; Primärdatensatz für Lucoris). Kleingeschriebenes {@code .csv}! */
    GKG(".gkg.csv.zip", "gkg");

    private final String urlSuffix;
    private final String logName;

    GdeltDataset(String urlSuffix, String logName) {
        this.urlSuffix = urlSuffix;
        this.logName = logName;
    }

    /** Datensatzname für {@code ingest_log.dataset}. */
    public String logName() {
        return logName;
    }

    /**
     * Baut die vollständige Slice-URL {@code <baseUrl>/<stamp><suffix>}.
     *
     * @param baseUrl GDELT-Basis-URL (z.B. {@code http://data.gdeltproject.org/gdeltv2})
     * @param stamp   14-stelliger UTC-Zeitstempel {@code yyyyMMddHHmmss} des Slices
     */
    public String url(String baseUrl, String stamp) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + "/" + stamp + urlSuffix;
    }

    /**
     * GDELT-Dateiname des Slices ({@code <stamp><suffix>}) — Schlüssel für {@code ingest_log}.
     *
     * @param stamp 14-stelliger UTC-Zeitstempel {@code yyyyMMddHHmmss} des Slices
     */
    public String filename(String stamp) {
        return stamp + urlSuffix;
    }
}
