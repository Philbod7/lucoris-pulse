package com.lucoris.pulse.ingest.gdelt;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Null-sichere Feldparser für GDELT-V2-Rohzeilen. GDELT-Felder sind TAB-getrennt und unquoted;
 * leere Felder werden als {@code null} abgebildet. Alle GDELT-Zeitstempel sind UTC.
 *
 * <p>Reines POJO ohne Spring/Netz — direkt unit-testbar.
 */
public final class GdeltParsing {

    private static final DateTimeFormatter TS_14 = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DAY_8 = DateTimeFormatter.ofPattern("yyyyMMdd");

    private GdeltParsing() {
    }

    /** Liefert das Feld am Index oder {@code null}, wenn die Zeile kürzer ist (defensiv). */
    public static String at(String[] cols, int index) {
        return index < cols.length ? str(cols[index]) : null;
    }

    /** Trimmt; leerer String wird zu {@code null}. */
    public static String str(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    /** {@code yyyyMMddHHmmss} (UTC) -> {@link Instant}. */
    public static Instant ts(String v) {
        String s = str(v);
        return s == null ? null : LocalDateTime.parse(s, TS_14).toInstant(ZoneOffset.UTC);
    }

    /** {@code yyyyMMdd} -> {@link LocalDate}. */
    public static LocalDate day(String v) {
        String s = str(v);
        return s == null ? null : LocalDate.parse(s, DAY_8);
    }

    public static Long longVal(String v) {
        String s = str(v);
        return s == null ? null : Long.valueOf(s);
    }

    public static Integer intVal(String v) {
        String s = str(v);
        return s == null ? null : Integer.valueOf(s);
    }

    public static Short shortVal(String v) {
        String s = str(v);
        return s == null ? null : Short.valueOf(s);
    }

    public static BigDecimal decimal(String v) {
        String s = str(v);
        return s == null ? null : new BigDecimal(s);
    }

    /** GDELT kodiert Flags als {@code "1"}/{@code "0"}. */
    public static Boolean bool01(String v) {
        String s = str(v);
        return s == null ? null : "1".equals(s);
    }
}
