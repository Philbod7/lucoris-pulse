package com.lucoris.pulse.ingest.primary;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port (Infrastruktur): holt einen Feed. Kein Domänenwissen, kein Parsen.
 *
 * <p>Bewusst {@code byte[]} und nicht {@code String}: der Fed-Feed beginnt mit einem UTF-8-BOM, der
 * ECB-Feed hat gar keine XML-Deklaration. Ein vorschnelles {@code new String(bytes, UTF_8)} würde
 * den XML-Parser mit „Content is not allowed in prolog" scheitern lassen — die Bytes gehören
 * unangetastet in Romes {@code XmlReader}, der Prolog, BOM und Zeichensatz korrekt auflöst.
 *
 * <p>Die Antwort-Header werden mitgeliefert, weil der TDM-Vorbehalt auch als HTTP-Header
 * {@code TDM-Reservation} kommen kann — eine Aussage über die ausgelieferte Ressource, die
 * definitionsgemäß erst in der Antwort steht. Der {@code TdmAwareFeedFetcher} wertet sie aus.
 *
 * <p>Dieser Port ist zugleich die Offline-Zusicherung der Standard-Tests: weil
 * {@link GenericRssAdapter} nur ihn kennt und keinen {@code HttpClient}, ist ein versehentlicher
 * Netz-Zugriff im Unit-Test nicht möglich — nicht bloß ungetestet.
 */
public interface FeedFetcher {

    /**
     * @param url die Feed-URL
     * @return die Antwort, oder {@link Optional#empty()}, wenn der Feed nicht abrufbar ist
     *         (z.B. HTTP 404/403, Timeout) — kein Werfen
     */
    Optional<FeedResponse> fetch(URI url);

    /**
     * @param body    die unangetasteten Roh-Bytes
     * @param headers die Antwort-Header (Namen case-insensitiv behandeln)
     */
    record FeedResponse(byte[] body, Map<String, List<String>> headers) {

        /** Für Fakes und Quellen ohne Header-Interesse. */
        public FeedResponse(byte[] body) {
            this(body, Map.of());
        }
    }
}
