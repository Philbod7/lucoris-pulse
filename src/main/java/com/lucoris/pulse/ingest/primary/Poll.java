package com.lucoris.pulse.ingest.primary;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Abruf-Rhythmus einer Quelle. Entweder {@code mode=interval} mit {@link #seconds()} oder
 * {@code mode=calendar} mit {@link #ref()} (Termin-Seite des Herausgebers) — nie beides.
 *
 * <p>{@code seconds} ist bewusst ein {@link Integer} (nicht {@code int}): bei Kalender-Quellen
 * fehlt das Feld, und ein primitives {@code int} würde daraus stillschweigend {@code 0} machen —
 * ununterscheidbar von „Intervall 0 Sekunden".
 *
 * @param mode    {@code interval} oder {@code calendar}
 * @param seconds Abruf-Intervall in Sekunden; {@code null} bei {@code mode=calendar}
 * @param ref     URL der Veröffentlichungs-Termine; {@code null} bei {@code mode=interval}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Poll(String mode, Integer seconds, String ref) {}
