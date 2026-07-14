package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Attributionspflicht einer Quelle (Lizenz-/Nutzungsbedingung). Fehlt der Block in der Quelle, gibt
 * es keine Pflicht — die Anzeige-Regel (Institution + Datum + Deep-Link) gilt trotzdem immer.
 *
 * @param required     Quellenangabe ist Lizenz-/Nutzungsbedingung
 * @param formula      vorgegebene/übliche Formel, z.B. {@code "Quelle: Europaeische Zentralbank, [Titel/Datum]"}
 * @param modifiedNote bei Bearbeitung ist ein Veränderungshinweis nötig (z.B. dl-de/by-2.0:
 *                     „eigene Darstellung auf Basis von ...")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Attribution(
        boolean required,
        String formula,
        @JsonProperty("modified_note") boolean modifiedNote) {}
