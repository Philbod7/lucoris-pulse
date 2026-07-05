package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Zusammengesetzter Schlüssel der partitionierten Tabelle {@code gdelt_mentions}:
 * Surrogat {@code mention_id} (aus {@code mention_seq}) plus Partitionsschlüssel
 * {@code mention_time_date}. Feldnamen entsprechen den {@code @Id}-Feldern der Entity.
 */
public class MentionId implements Serializable {

    private Long mentionId;
    private Instant mentionTimeDate;

    public MentionId() {
    }

    public MentionId(Long mentionId, Instant mentionTimeDate) {
        this.mentionId = mentionId;
        this.mentionTimeDate = mentionTimeDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MentionId that)) {
            return false;
        }
        return Objects.equals(mentionId, that.mentionId)
                && Objects.equals(mentionTimeDate, that.mentionTimeDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mentionId, mentionTimeDate);
    }
}
