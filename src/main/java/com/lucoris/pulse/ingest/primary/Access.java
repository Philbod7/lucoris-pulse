package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Zugriffsbeschreibung einer Quelle aus dem Routing-Manifest.
 *
 * @param type   Dispatch-Kategorie: {@code rss} | {@code api} | {@code landing}
 * @param url    Abruf-URL (bei {@code confidence != verified} beim Einbau zu fixieren)
 * @param format Nutzlast-Form: {@code rss2.0} | {@code atom} | {@code json} | {@code html} |
 *               {@code webservice}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Access(String type, String url, String format) {}
