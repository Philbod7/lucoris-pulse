package com.lucoris.pulse.ingest.primary.robots;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Der dritte Vorbehaltskanal: die HTTP-Header {@code TDM-Reservation} und {@code TDM-Policy} (TDM
 * Reservation Protocol).
 *
 * <p>Anders als robots.txt und {@code tdmrep.json} ist er eine Aussage über die AUSGELIEFERTE
 * RESSOURCE, nicht über die Domain — er ist deshalb erst in der Antwort lesbar. Genau so ist er
 * gedacht: § 44b UrhG verbietet das Mining, nicht den Abruf. Wir lesen den Header und verwerfen das
 * Dokument, bevor irgendetwas damit geschieht.
 */
public final class TdmHeader {

    private static final String RESERVATION = "tdm-reservation";
    private static final String POLICY = "tdm-policy";

    private TdmHeader() {}

    /** Erklärt die Antwort einen TDM-Vorbehalt ({@code TDM-Reservation: 1} oder höher)? */
    public static boolean isReserved(Map<String, List<String>> headers) {
        return firstValue(headers, RESERVATION)
                .map(TdmHeader::isPositive)
                .orElse(false);
    }

    /** Die zugehörige Policy-URL, falls angegeben — für die Beweislast-Zeile. */
    public static Optional<String> policy(Map<String, List<String>> headers) {
        return firstValue(headers, POLICY);
    }

    /** {@code 0} heißt ausdrücklich KEIN Vorbehalt; alles >= 1 ist einer. */
    private static boolean isPositive(String value) {
        try {
            return Integer.parseInt(value.trim()) >= 1;
        } catch (NumberFormatException e) {
            return false; // unlesbarer Wert ist keine Erklärung
        }
    }

    /** HTTP-Header-Namen sind case-insensitiv (RFC 9110) — der Server schreibt sie, wie er mag. */
    private static Optional<String> firstValue(Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(name))
                .map(Map.Entry::getValue)
                .filter(values -> values != null && !values.isEmpty())
                .map(values -> values.getFirst())
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
