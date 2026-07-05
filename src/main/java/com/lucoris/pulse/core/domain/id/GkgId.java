package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der partitionierten Tabelle {@code gdelt_gkg}: natürliche
 * GKG-Datensatz-ID {@code gkg_record_id} plus Partitionsschlüssel {@code seen_date}.
 */
public class GkgId implements Serializable {

    private String gkgRecordId;
    private Instant seenDate;

    public GkgId() {
    }

    public GkgId(String gkgRecordId, Instant seenDate) {
        this.gkgRecordId = gkgRecordId;
        this.seenDate = seenDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GkgId that)) {
            return false;
        }
        return Objects.equals(gkgRecordId, that.gkgRecordId)
                && Objects.equals(seenDate, that.seenDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gkgRecordId, seenDate);
    }
}
