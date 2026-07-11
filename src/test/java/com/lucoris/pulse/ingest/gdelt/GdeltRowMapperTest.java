package com.lucoris.pulse.ingest.gdelt;

import static org.assertj.core.api.Assertions.assertThat;

import com.lucoris.pulse.core.domain.GdeltEvent;
import com.lucoris.pulse.core.domain.GdeltGkg;
import com.lucoris.pulse.core.domain.GdeltMention;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit-Tests der Spalten->Entity-Abbildung. Reine POJO-Tests ohne Spring/Container/Netz —
 * sie sichern die kritischen (leicht zu vertauschenden) GDELT-Spaltenindizes deterministisch ab.
 */
class GdeltRowMapperTest {

    /** Zeile mit {@code size} leeren TAB-Feldern (Index 0..size-1). */
    private static String[] emptyRow(int size) {
        String[] row = new String[size];
        Arrays.fill(row, "");
        return row;
    }

    @Test
    void mapsEventKeyColumnsIncludingActionGeoAndDateAdded() {
        String[] c = emptyRow(61);
        c[0] = "123456789";
        c[1] = "20260710";
        c[5] = "USA";
        c[6] = "UNITED STATES";
        c[7] = "USA";
        c[12] = "GOV";
        c[15] = "CHN";
        c[16] = "CHINA";
        c[17] = "CHN";
        c[22] = "BUS";
        c[25] = "1";
        c[26] = "043";
        c[27] = "043";
        c[28] = "04";
        c[29] = "1";
        c[30] = "2.5";
        c[31] = "10";
        c[32] = "3";
        c[33] = "8";
        c[34] = "-1.75";
        c[51] = "3";
        c[52] = "Berlin, Berlin, Germany";
        c[53] = "GM";
        c[56] = "52.52";
        c[57] = "13.405";
        c[59] = "20260710123000";
        c[60] = "https://example.com/article";

        GdeltEvent e = new GdeltEventRowMapper().map(c);

        assertThat(e).isNotNull();
        assertThat(e.getGlobalEventId()).isEqualTo(123456789L);
        assertThat(e.getDay()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(e.getActor1Code()).isEqualTo("USA");
        assertThat(e.getActor1Type1Code()).isEqualTo("GOV");
        assertThat(e.getActor2Type1Code()).isEqualTo("BUS");
        assertThat(e.getRootEvent()).isTrue();
        assertThat(e.getQuadClass()).isEqualTo((short) 1);
        assertThat(e.getGoldsteinScale()).isEqualByComparingTo("2.5");
        assertThat(e.getNumMentions()).isEqualTo(10);
        assertThat(e.getAvgTone()).isEqualByComparingTo("-1.75");
        assertThat(e.getActionGeoType()).isEqualTo((short) 3);
        assertThat(e.getActionGeoFullname()).isEqualTo("Berlin, Berlin, Germany");
        assertThat(e.getActionGeoCountryCode()).isEqualTo("GM");
        assertThat(e.getActionGeoLat()).isEqualByComparingTo("52.52");
        assertThat(e.getActionGeoLong()).isEqualByComparingTo("13.405");
        assertThat(e.getDateAdded()).isEqualTo(Instant.parse("2026-07-10T12:30:00Z"));
        assertThat(e.getSourceUrl()).isEqualTo("https://example.com/article");
    }

    @Test
    void eventShortRowIsRejected() {
        assertThat(new GdeltEventRowMapper().map(emptyRow(10))).isNull();
    }

    @Test
    void mapsMentionKeyColumnsAndLeavesSurrogateNull() {
        String[] c = emptyRow(16);
        c[0] = "123456789";
        c[1] = "20260710120000";
        c[2] = "20260710123000";
        c[3] = "1";
        c[4] = "example.com";
        c[5] = "https://example.com/article";
        c[6] = "4";
        c[11] = "80";
        c[13] = "-2.5";

        GdeltMention m = new GdeltMentionRowMapper().map(c);

        assertThat(m).isNotNull();
        assertThat(m.getMentionId()).isNull(); // wird beim Insert aus mention_seq vergeben
        assertThat(m.getGlobalEventId()).isEqualTo(123456789L);
        assertThat(m.getEventTimeDate()).isEqualTo(Instant.parse("2026-07-10T12:00:00Z"));
        assertThat(m.getMentionTimeDate()).isEqualTo(Instant.parse("2026-07-10T12:30:00Z"));
        assertThat(m.getMentionType()).isEqualTo((short) 1);
        assertThat(m.getMentionSourceName()).isEqualTo("example.com");
        assertThat(m.getMentionIdentifier()).isEqualTo("https://example.com/article");
        assertThat(m.getSentenceId()).isEqualTo(4);
        assertThat(m.getConfidence()).isEqualTo((short) 80);
        assertThat(m.getMentionDocTone()).isEqualByComparingTo("-2.5");
    }

    @Test
    void mapsGkgV2EnhancedColumnsAndParsesLeadingTone() {
        String[] c = emptyRow(27);
        c[0] = "20260710123000-42";
        c[1] = "20260710123000";
        c[3] = "example.com";
        c[4] = "https://example.com/article";
        c[8] = "ECON_STOCKMARKET,120;EPU_POLICY,300";
        c[10] = "1#Germany#GM#GM#51#9#GM";
        c[12] = "Angela Merkel,45";
        c[14] = "European Central Bank,88";
        c[15] = "-3.4,2.1,5.5,7.6,20,1,150";
        c[23] = "Angela Merkel,45";

        GdeltGkg g = new GdeltGkgRowMapper().map(c);

        assertThat(g).isNotNull();
        assertThat(g.getGkgRecordId()).isEqualTo("20260710123000-42");
        assertThat(g.getSeenDate()).isEqualTo(Instant.parse("2026-07-10T12:30:00Z"));
        assertThat(g.getSourceCommonName()).isEqualTo("example.com");
        assertThat(g.getDocumentIdentifier()).isEqualTo("https://example.com/article");
        assertThat(g.getV2Themes()).isEqualTo("ECON_STOCKMARKET,120;EPU_POLICY,300");
        assertThat(g.getV2Locations()).isEqualTo("1#Germany#GM#GM#51#9#GM");
        assertThat(g.getV2Persons()).isEqualTo("Angela Merkel,45");
        assertThat(g.getV2Organizations()).isEqualTo("European Central Bank,88");
        assertThat(g.getV2AllNames()).isEqualTo("Angela Merkel,45");
        assertThat(g.getV2Tone()).isEqualTo("-3.4,2.1,5.5,7.6,20,1,150");
        assertThat(g.getTone()).isEqualByComparingTo("-3.4");
    }

    @Test
    void gkgShortRowIsRejected() {
        assertThat(new GdeltGkgRowMapper().map(emptyRow(5))).isNull();
    }
}
