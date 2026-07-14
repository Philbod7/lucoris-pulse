package com.lucoris.pulse.ingest.primary.robots;

import java.net.URI;
import java.util.Objects;

/**
 * Was das Gate über einen beabsichtigten Abruf wissen muss.
 *
 * <p>Bewusst KEIN {@code IngestSource}: der robots-Kern soll das Registry-Modell nicht kennen (die
 * Abhängigkeit zeigt nur in eine Richtung). Bewusst auch keine losen Einzelparameter — jede neue
 * Bedingung würde die Signatur verbreitern und Aufrufer zu {@code null, null} verleiten.
 *
 * <p>Es gibt absichtlich keine bequeme {@code check(URI)}-Überladung am Gate: wer ein
 * {@code FetchIntent} ohne Einladung baut, bekommt {@code BLOCKED}. Vergessen fällt damit in die
 * sichere Richtung — eine Abkürzung wäre genau das Loch, durch das die Evidenz still verlorenginge.
 *
 * @param sourceId   {@code id} der Quelle aus der Registry (für die Beweislast-Zeile)
 * @param url        die URL, die geholt werden soll (nicht die robots.txt)
 * @param accessType {@code access.type} der Quelle — nur {@code rss} kann eine Einladung tragen (a)
 * @param invitation ausdrückliche Abo-Einladung; {@code null} = keine (der Normalfall)
 */
public record FetchIntent(String sourceId, URI url, String accessType, ExpressInvitation invitation) {

    /** Der Typ, für den eine Einladung überhaupt in Frage kommt (Bedingung a). */
    public static final String RSS = "rss";

    public FetchIntent {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(url, "url");
    }

    public boolean isRss() {
        return RSS.equals(accessType);
    }
}
