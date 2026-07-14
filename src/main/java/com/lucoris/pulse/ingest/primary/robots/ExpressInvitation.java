package com.lucoris.pulse.ingest.primary.robots;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Die ausdrückliche Einladung eines Herausgebers, seinen Feed maschinell zu lesen — die EINZIGE
 * Evidenz, die ein generisches robots-Disallow aufwiegen kann.
 *
 * <p>Sie kommt ausschließlich aus der Registry und wird dort von Hand eingetragen: es gibt kein
 * Force-Flag, keine Sonderbehandlung einzelner Quellen im Code, und der Code erfindet sie nie. Der
 * Grund ist die Beweislast — wer sich auf eine Einladung beruft, muss sagen können, wo sie steht,
 * wie sie wörtlich lautet und wann er sie gelesen hat.
 *
 * <p>Die Einladung erlaubt den <strong>Abruf</strong>. Sie ist keine Lizenz: {@code legal_class}
 * bleibt, was sie ist.
 *
 * @param pageUrl   Seite, auf der die Einladung steht — nachprüfbar
 * @param wording   der wörtliche Satz, auf den wir uns berufen (Match-Ziel der Re-Validierung)
 * @param retrieved Tag der Feststellung, ISO-8601 ({@code 2026-07-13}). Evidenz veraltet.
 * @param scope     optionale Einschränkung im Klartext; dokumentarisch (siehe ADR 24)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpressInvitation(
        @JsonProperty("page_url") String pageUrl,
        String wording,
        String retrieved,
        String scope) {

    /** Vollständig = die drei Nachweisfelder sind da und {@code retrieved} ist ein lesbares Datum. */
    public boolean complete() {
        return notBlank(pageUrl) && notBlank(wording) && retrievedDate().isPresent();
    }

    /**
     * Der Tag der Feststellung.
     *
     * <p>{@code retrieved} ist im Record bewusst ein {@link String} und wird erst hier zu einem
     * {@link LocalDate}: das Manifest wird mit einem nackten {@code JsonMapper.builder().build()}
     * gelesen, und ohne registriertes java.time-Modul würde ein {@code LocalDate}-Feld die
     * Deserialisierung der GESAMTEN Registry reißen — der Ingest wäre dann still leer. Ein
     * unlesbares Datum macht die Einladung hier nur unvollständig, und das heißt: verboten.
     */
    public Optional<LocalDate> retrievedDate() {
        if (!notBlank(retrieved)) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(retrieved.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Ist die Feststellung älter als {@code maxAge}? Evidenz veraltet — eine Einladung, die 2026
     * gelesen wurde, trägt einen Abruf 2031 nicht mehr.
     */
    public boolean olderThan(Duration maxAge, Instant now) {
        return retrievedDate()
                .map(date -> date.atStartOfDay(ZoneOffset.UTC).toInstant().plus(maxAge).isBefore(now))
                .orElse(true); // kein lesbares Datum = keine belastbare Feststellung
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
