package com.lucoris.pulse.ingest.primary.robots;

/**
 * Der Abruf einer Quelle ist untersagt (robots.txt-Verbot, KI-/TDM-Vorbehalt, oder die Erlaubnis
 * ließ sich nicht feststellen).
 *
 * <p>Bewusst eine Exception und kein stilles „leere Liste": eine verbotene Quelle sähe sonst aus
 * wie ein leerer Feed. Der Ingest fängt sie pro Quelle ab (die übrigen laufen weiter) und die
 * Load-Validierung meldet sie gesondert.
 */
public class SourceNotPermittedException extends RuntimeException {

    public SourceNotPermittedException(String message) {
        super(message);
    }
}
