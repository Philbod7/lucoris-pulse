package com.lucoris.pulse.ingest.primary;

import java.time.Instant;
import java.util.Optional;

/**
 * Port: wann eine Quelle zuletzt ERFOLGREICH gelesen wurde.
 *
 * <p>Für Adapter, die inkrementell arbeiten können: {@link SecEdgarDailyIndexAdapter} holt sonst bei
 * jedem Lauf die volle Rückschau, obwohl im Normalbetrieb ein einziger Tag genügt.
 *
 * <p>Die Auskunft kommt bewusst aus {@code primary_source_state} (persistiert), NICHT aus einem Feld
 * im Adapter: ein Wert im Arbeitsspeicher wäre nach einem Neustart weg — also genau in dem Fall, für
 * den die lange Rückschau überhaupt existiert. Ein Adapter, der sich nach jedem Deploy für
 * „frisch gestartet" hält, holt entweder jedes Mal alles oder verliert still Tage.
 *
 * <p>Kein Zustand ({@link Optional#empty()}) heißt „noch nie erfolgreich" und damit: volle Rückschau.
 * Das ist auch die richtige Antwort dort, wo es gar keinen Store gibt (Profil
 * {@code validate-sources}, Proben) — dann soll der Adapter zeigen, was er maximal kann.
 */
@FunctionalInterface
public interface LastSuccessLookup {

    /**
     * @param sourceId {@code id} der Quelle aus dem Manifest
     * @return Zeitpunkt des letzten erfolgreichen Laufs; leer, wenn es keinen gab oder kein Zustand
     *         geführt wird
     */
    Optional<Instant> lastSuccessOf(String sourceId);
}
