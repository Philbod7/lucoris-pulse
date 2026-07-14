package com.lucoris.pulse.ingest.primary;

/**
 * Ergebnis EINES Quellen-Laufs — die Zeile der Betriebs-Summary „gefetcht / neu / dedupliziert /
 * Fehler". Bei einem Fehler sind die Zähler 0 und {@link #error()} trägt die Meldung.
 *
 * @param sourceId Manifest-id der Quelle
 * @param fetched  Einträge, die der Feed geliefert hat
 * @param newItems davon neu gespeichert
 * @param deduped  davon Dubletten (schon gespeichert oder batch-intern doppelt)
 * @param error    Fehlermeldung; {@code null} bei Erfolg
 */
public record SourceRunResult(String sourceId, int fetched, int newItems, int deduped, String error) {

    public static SourceRunResult success(String sourceId, int fetched, int newItems, int deduped) {
        return new SourceRunResult(sourceId, fetched, newItems, deduped, null);
    }

    public static SourceRunResult failure(String sourceId, String error) {
        return new SourceRunResult(sourceId, 0, 0, 0, error);
    }

    public boolean failed() {
        return error != null;
    }
}
