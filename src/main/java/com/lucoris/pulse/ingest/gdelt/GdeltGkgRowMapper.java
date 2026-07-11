package com.lucoris.pulse.ingest.gdelt;

import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.decimal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.str;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.ts;

import com.lucoris.pulse.core.domain.GdeltGkg;

/**
 * Bildet eine GDELT-2.1-GKG-Rohzeile (27 TAB-Spalten, Index 0..26) auf {@link GdeltGkg} ab.
 * Reines POJO. Es werden die V2-Enhanced-Listen (Themes 8, Locations 10, Persons 12,
 * Organizations 14) verwendet, NICHT die parallelen V1-Spalten (7/9/11/13) — passend zu den
 * {@code v2_*}-Feldnamen und dem {@code #}/{@code ;}-Format der Enhanced-Locations. {@code tone}
 * ist das erste Feld des Roh-Tontupels (V1.5Tone, Index 15) vor dem ersten Komma.
 */
public final class GdeltGkgRowMapper {

    /** GKG 2.1 hat 27 Spalten. */
    static final int MIN_COLUMNS = 27;

    /** @return gemappter GKG-Datensatz oder {@code null}, wenn die Zeile zu wenige Spalten hat. */
    public GdeltGkg map(String[] c) {
        if (c == null || c.length < MIN_COLUMNS) {
            return null;
        }
        GdeltGkg g = new GdeltGkg();
        g.setGkgRecordId(str(c[0]));                 // NOT NULL (PK-Teil)
        g.setSeenDate(ts(c[1]));                     // V2.1DATE, Partitionsschlüssel (NOT NULL)
        g.setSourceCommonName(str(c[3]));            // Index 2 = SourceCollectionIdentifier
        g.setDocumentIdentifier(str(c[4]));          // Artikel-URL
        g.setV2Themes(str(c[8]));                    // V2EnhancedThemes
        g.setV2Locations(str(c[10]));                // V2EnhancedLocations
        g.setV2Persons(str(c[12]));                  // V2EnhancedPersons
        g.setV2Organizations(str(c[14]));            // V2EnhancedOrganizations
        g.setV2AllNames(str(c[23]));                 // V2.1AllNames
        String toneTuple = str(c[15]);               // V1.5Tone: "tone,pos,neg,polarity,..."
        g.setV2Tone(toneTuple);
        g.setTone(firstTone(toneTuple));
        return g;
    }

    /** Erstes Feld des Tontupels (Haupttonwert) als {@link java.math.BigDecimal}. */
    private static java.math.BigDecimal firstTone(String toneTuple) {
        if (toneTuple == null) {
            return null;
        }
        int comma = toneTuple.indexOf(',');
        String first = comma >= 0 ? toneTuple.substring(0, comma) : toneTuple;
        return decimal(first);
    }
}
