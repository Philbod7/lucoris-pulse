package com.lucoris.pulse.ingest.primary.robots;

import java.net.URI;

/**
 * Port (Infrastruktur): holt die Erlaubnis-Dokumente einer Domain ({@code robots.txt},
 * {@code /.well-known/tdmrep.json}).
 *
 * <p>Anders als {@code FeedFetcher} MUSS dieser Port den HTTP-Status durchreichen: für die
 * Fail-closed-Regel ist der Unterschied zwischen „404 — es gibt keine robots.txt, also keine
 * Einschränkung" und „503 — wir wissen es nicht" der ganze Punkt. Ein {@code Optional.empty()}
 * würde beides zu einem Topf verrühren.
 */
public interface PolicyFetcher {

    /** {@link Response#status()} ist {@link Response#NETWORK_ERROR}, wenn gar keine Antwort kam. */
    Response get(URI url);

    /**
     * @param status  HTTP-Status, oder {@link #NETWORK_ERROR} bei Timeout/DNS-Fehler/Abbruch
     * @param body    Antwortkörper; leer, wenn kein Erfolg
     * @param headers Antwort-Header (Namen case-insensitiv behandeln) — für den
     *                {@code TDM-Reservation}-Header auf der Einladungsseite
     */
    record Response(int status, String body, java.util.Map<String, java.util.List<String>> headers) {

        /** Kein HTTP-Status zustande gekommen (Timeout, DNS, Verbindungsabbruch). */
        public static final int NETWORK_ERROR = 0;

        /** Ohne Header — für Fakes und alles, was die Header nicht braucht. */
        public Response(int status, String body) {
            this(status, body, java.util.Map.of());
        }

        public static Response networkError() {
            return new Response(NETWORK_ERROR, "");
        }

        public boolean ok() {
            return status == 200;
        }

        /** Dokument gibt es nicht — bei robots.txt heißt das: keine Einschränkung (RFC 9309). */
        public boolean absent() {
            return status == 404 || status == 410;
        }

        /**
         * Wir konnten die Erlaubnis NICHT feststellen: Netzfehler, Serverfehler (5xx),
         * Zugriffsverweigerung (401/403) oder Drosselung (429). RFC 9309 § 2.3.1.4 wertet all das
         * als „unavailable" und damit als vollständiges Verbot.
         *
         * <p>429 gehört ausdrücklich dazu: wer uns drosselt, hat uns die Hausordnung nicht gezeigt.
         * Ein gedrosseltes robots.txt als „keine Regeln, also alles erlaubt" zu lesen, wäre die
         * Antwort genau falsch herum — gerade bei Hosts mit hartem Ratenlimit (SEC EDGAR: 10 Req/s).
         */
        public boolean unknown() {
            return status == NETWORK_ERROR || status >= 500
                    || status == 401 || status == 403 || status == 429;
        }
    }
}
