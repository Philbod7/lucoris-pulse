package com.lucoris.pulse.ingest.primary;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Berechnet den quellenübergreifenden Deduplizierungsschlüssel einer {@link FeedItem}-Meldung.
 *
 * <p>Regel: die vom Feed deklarierte guid, wenn sie URL-förmig ist (normalisiert) — sonst der
 * normalisierte Link. Bewusst NICHT {@code sourceId + url}: dieselbe Meldung erscheint in
 * überlappenden Feeds (z.B. bmf-presse und bmf-finanzmarkt, guid = Artikel-URL) und darf nur
 * einmal gespeichert werden. Opake guids ({@code "12345"}, {@code "tag:..."}) werden nie roh
 * verwendet — zwei Herausgeber könnten zufällig dieselbe ID vergeben; der Link dedupliziert dann.
 *
 * <p>Die Normalisierung entfernt nur, was nachweislich nicht zur Identität der Ressource gehört
 * (Tracking-Parameter, Fragment, Groß-/Kleinschreibung von Scheme/Host, Default-Ports). Pfad und
 * übrige Query-Parameter bleiben unangetastet — deren Semantik bestimmt der Server.
 *
 * <p>Pures POJO ohne Abhängigkeiten; wirft nie — eine unparsebare URL wird getrimmt roh verwendet,
 * ein kaputter Eintrag darf den Batch nicht mitreißen.
 */
public final class DedupKeys {

    /** Tracking-Parameter, die exakt (case-insensitiv) entfernt werden. */
    private static final Set<String> TRACKING_PARAMS =
            Set.of("fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "spm");

    /** Tracking-Parameter-Präfixe (case-insensitiv), z.B. utm_source, utm_campaign. */
    private static final String TRACKING_PREFIX = "utm_";

    private DedupKeys() {}

    /** Der Deduplizierungsschlüssel der Meldung: URL-förmige guid vor Link, beides normalisiert. */
    public static String keyFor(FeedItem item) {
        String guid = item.guid();
        if (isHttpUrl(guid)) {
            return normalizeUrl(guid);
        }
        return normalizeUrl(item.url());
    }

    /**
     * Normalisiert eine URL für den Schlüsselvergleich. Öffentlich, damit ein späterer Resolver
     * (Meldung ↔ {@code url_index}) mit exakt derselben Normalform arbeiten kann.
     */
    public static String normalizeUrl(String raw) {
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return trimmed;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return trimmed;
        }

        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }

        StringBuilder normalized = new StringBuilder(trimmed.length());
        normalized.append(scheme).append("://");
        if (uri.getRawUserInfo() != null) {
            normalized.append(uri.getRawUserInfo()).append('@');
        }
        normalized.append(host);
        if (port != -1) {
            normalized.append(':').append(port);
        }
        if (uri.getRawPath() != null) {
            normalized.append(uri.getRawPath());
        }
        String query = withoutTrackingParams(uri.getRawQuery());
        if (query != null) {
            normalized.append('?').append(query);
        }
        // Fragment bewusst weggelassen: #abschnitt adressiert eine Stelle IM Dokument, nicht ein anderes.
        return normalized.toString();
    }

    /** Entfernt Tracking-Parameter; {@code null} wenn keine Query bleibt. Reihenfolge bleibt erhalten. */
    private static String withoutTrackingParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String param : rawQuery.split("&")) {
            String name = param.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.startsWith(TRACKING_PREFIX) || TRACKING_PARAMS.contains(name)) {
                continue;
            }
            kept.add(param);
        }
        return kept.isEmpty() ? null : String.join("&", kept);
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        try {
            return new URI(value.trim()).getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
