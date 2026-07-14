package com.lucoris.pulse.ingest.primary;

import java.time.Instant;

/**
 * Eine Feed-Meldung aus einer Primärquelle — das gemeinsame Ausgabeformat ALLER Quellen-Adapter
 * (RSS heute, {@code sec_edgar} und die übrigen Handler später). Das Routing-Manifest gibt genau
 * das vor: „unten emittieren alle Adapter dasselbe FeedItem".
 *
 * <p>Bewusst NICHT „Event" genannt: das hier ist die Meldung (das RSS/Atom-Item), nicht das reale
 * Ereignis. Mehrere Meldungen zum selben Ereignis bleiben getrennt — ihre Zusammenführung (auch
 * mit GDELT über {@code url_index}) ist eine spätere Resolver-Entität.
 *
 * <p>{@code legalClass} und {@code attribution} werden unverändert aus der Quelle durchgereicht,
 * damit das spätere Rendering die Quellzeile bauen kann: Institution + Datum + Deep-Link ist immer
 * zu zeigen, bei Attributionspflicht zusätzlich die vorgegebene Formel.
 *
 * <p>Ein {@code eventType} fehlt bewusst — die Klassifikation passiert später beim Routing, nicht
 * beim Einlesen.
 *
 * @param sourceId    {@code id} der Quelle aus dem Manifest, z.B. {@code ecb-press}
 * @param institution Klarname des Herausgebers (für die Quellzeile)
 * @param title       Titel des Eintrags
 * @param url         Deep-Link auf das Original; Pflicht (Einträge ohne Link werden verworfen)
 * @param guid        vom Feed deklarierte Kennung der Meldung (RSS {@code guid}, Atom {@code id});
 *                    {@code null}, wenn der Feed keine liefert. Grundlage der Deduplizierung —
 *                    siehe {@link DedupKeys}
 * @param publishedAt Veröffentlichungszeitpunkt, nach UTC normalisiert; Pflicht (Einträge ohne
 *                    parsbares Datum werden verworfen)
 * @param rawSummary  Rohtext aus {@code content:encoded} bzw. {@code description}/{@code summary};
 *                    {@code null}, wenn die Quelle keinen liefert (z.B. der ECB-Feed)
 * @param language    Sprache laut Feed; {@code null} möglich
 * @param fetchedAt   Zeitpunkt unseres Abrufs (UTC)
 * @param legalClass  {@code A} oder {@code B}, aus der Quelle durchgereicht
 * @param attribution Attributionspflicht der Quelle; {@code null}, wenn keine besteht
 */
public record FeedItem(
        String sourceId,
        String institution,
        String title,
        String url,
        String guid,
        Instant publishedAt,
        String rawSummary,
        String language,
        Instant fetchedAt,
        String legalClass,
        Attribution attribution) {}
