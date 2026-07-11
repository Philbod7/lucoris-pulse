package com.lucoris.pulse.ingest.gdelt;

import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.bool01;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.day;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.decimal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.intVal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.longVal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.shortVal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.str;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.ts;

import com.lucoris.pulse.core.domain.GdeltEvent;

/**
 * Bildet eine GDELT-2.0-Events-Rohzeile (61 TAB-Spalten, Index 0..60) auf {@link GdeltEvent} ab.
 * Reines POJO. Spaltenindizes nach GDELT-2.0-Codebook — insbesondere die ActionGeo-Felder
 * (51..57), NICHT der zuvor stehende Actor1Geo-Block (35..41).
 */
public final class GdeltEventRowMapper {

    /** Events haben 61 Spalten; kürzere Zeilen sind strukturell ungültig. */
    static final int MIN_COLUMNS = 61;

    /** @return gemapptes Event oder {@code null}, wenn die Zeile zu wenige Spalten hat. */
    public GdeltEvent map(String[] c) {
        if (c == null || c.length < MIN_COLUMNS) {
            return null;
        }
        GdeltEvent e = new GdeltEvent();
        e.setGlobalEventId(longVal(c[0]));
        e.setDay(day(c[1]));                        // SQLDATE yyyyMMdd
        e.setActor1Code(str(c[5]));
        e.setActor1Name(str(c[6]));
        e.setActor1CountryCode(str(c[7]));
        e.setActor1Type1Code(str(c[12]));
        e.setActor2Code(str(c[15]));
        e.setActor2Name(str(c[16]));
        e.setActor2CountryCode(str(c[17]));
        e.setActor2Type1Code(str(c[22]));
        e.setRootEvent(bool01(c[25]));
        e.setEventCode(str(c[26]));
        e.setEventBaseCode(str(c[27]));
        e.setEventRootCode(str(c[28]));
        e.setQuadClass(shortVal(c[29]));
        e.setGoldsteinScale(decimal(c[30]));
        e.setNumMentions(intVal(c[31]));
        e.setNumSources(intVal(c[32]));
        e.setNumArticles(intVal(c[33]));
        e.setAvgTone(decimal(c[34]));
        e.setActionGeoType(shortVal(c[51]));         // ActionGeo-Block, nicht Actor1Geo (35..41)
        e.setActionGeoFullname(str(c[52]));
        e.setActionGeoCountryCode(str(c[53]));
        e.setActionGeoLat(decimal(c[56]));
        e.setActionGeoLong(decimal(c[57]));
        e.setDateAdded(ts(c[59]));                   // DATEADDED yyyyMMddHHmmss (NOT NULL)
        e.setSourceUrl(str(c[60]));
        return e;
    }
}
