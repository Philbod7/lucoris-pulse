package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Eine gepollte Quelle aus dem Routing-Manifest ({@code ingest_sources[]}).
 *
 * <p>Bewusst OHNE Kompakt-Konstruktor mit Null-Checks: {@code attribution} und {@code notes} fehlen
 * bei etlichen Quellen (z.B. {@code fed-monetary}), und eine Pflichtprüfung würde die
 * Deserialisierung genau dieser Quellen brechen.
 *
 * @param id          stabiler Schlüssel, z.B. {@code ecb-press}
 * @param institution Klarname des Herausgebers (geht in die Quellzeile des Renderings)
 * @param category    z.B. {@code central_bank}, {@code statistics_office}
 * @param region      z.B. {@code EA}, {@code US}, {@code DE}
 * @param tier        Wichtigkeitsstufe 1..3
 * @param originates  Ereignistypen, für die diese Quelle die originäre Ausgabestelle ist
 * @param access      wohin und in welcher Form abgerufen wird
 * @param handler     Adapter-Klasse für das Routing; bei RSS immer {@code generic_rss}
 * @param poll        Abruf-Rhythmus
 * @param enabled     {@code true} = wird jetzt verdrahtet und abgerufen
 * @param confidence  {@code verified} | {@code verify_endpoint} | {@code landing_only}
 * @param legalClass  {@code A} = amtlich/gemeinfrei/offene Lizenz, {@code B} = Text formal
 *                    geschützt (nur Fakten extrahieren, eigene Formulierung, Link)
 * @param attribution Attributionspflicht; {@code null}, wenn keine besteht
 * @param notes       Freitext-Hinweise; {@code null} möglich
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestSource(
        String id,
        String institution,
        String category,
        String region,
        int tier,
        List<String> originates,
        Access access,
        String handler,
        Poll poll,
        boolean enabled,
        String confidence,
        @JsonProperty("legal_class") String legalClass,
        Attribution attribution,
        String notes) {}
