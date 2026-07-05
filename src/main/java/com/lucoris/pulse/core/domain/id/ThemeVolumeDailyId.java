package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der Baseline-Tabelle {@code theme_volume_daily}: Tagesvolumen je
 * Thema als Paar ({@code theme_code}, {@code day}).
 */
public class ThemeVolumeDailyId implements Serializable {

    private String themeCode;
    private LocalDate day;

    public ThemeVolumeDailyId() {
    }

    public ThemeVolumeDailyId(String themeCode, LocalDate day) {
        this.themeCode = themeCode;
        this.day = day;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ThemeVolumeDailyId that)) {
            return false;
        }
        return Objects.equals(themeCode, that.themeCode)
                && Objects.equals(day, that.day);
    }

    @Override
    public int hashCode() {
        return Objects.hash(themeCode, day);
    }
}
