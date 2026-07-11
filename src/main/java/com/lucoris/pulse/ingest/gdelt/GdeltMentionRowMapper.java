package com.lucoris.pulse.ingest.gdelt;

import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.decimal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.intVal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.longVal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.shortVal;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.str;
import static com.lucoris.pulse.ingest.gdelt.GdeltParsing.ts;

import com.lucoris.pulse.core.domain.GdeltMention;

/**
 * Bildet eine GDELT-2.0-Mentions-Rohzeile (16 TAB-Spalten, Index 0..15) auf {@link GdeltMention}
 * ab. Reines POJO. {@code mentionId} bleibt {@code null} — das Surrogat wird beim StatelessSession-
 * Insert aus {@code mention_seq} vergeben. {@code confidence} liegt bei Index 11 (nach den
 * Char-Offsets 7..9 und InRawText 10), {@code mentionDocTone} bei 13 (nicht 12 = MentionDocLen).
 */
public final class GdeltMentionRowMapper {

    /** Mentions haben 16 Spalten. */
    static final int MIN_COLUMNS = 16;

    /** @return gemappte Mention oder {@code null}, wenn die Zeile zu wenige Spalten hat. */
    public GdeltMention map(String[] c) {
        if (c == null || c.length < MIN_COLUMNS) {
            return null;
        }
        GdeltMention m = new GdeltMention();
        m.setGlobalEventId(longVal(c[0]));
        m.setEventTimeDate(ts(c[1]));
        m.setMentionTimeDate(ts(c[2]));              // Partitionsschlüssel (NOT NULL)
        m.setMentionType(shortVal(c[3]));
        m.setMentionSourceName(str(c[4]));
        m.setMentionIdentifier(str(c[5]));           // Artikel-URL
        m.setSentenceId(intVal(c[6]));
        m.setConfidence(shortVal(c[11]));
        m.setMentionDocTone(decimal(c[13]));
        return m;
    }
}
