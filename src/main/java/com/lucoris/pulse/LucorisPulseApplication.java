package com.lucoris.pulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Einstiegspunkt des GDELT-Adapters {@code lucoris-pulse}.
 *
 * <p>Eigenständiges Vorsystem: liest GDELT ein, speichert normalisiert in PostgreSQL und
 * stellt aufbereitete News-Ereignisse über REST bereit. Ingest läuft hinter dem Spring-Profil
 * {@code ingest}, die REST-API ist immer aktiv. Das Schema gehört Flyway; Hibernate läuft mit
 * {@code ddl-auto=validate} und erzeugt kein DDL.
 */
@SpringBootApplication
public class LucorisPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(LucorisPulseApplication.class, args);
    }
}
