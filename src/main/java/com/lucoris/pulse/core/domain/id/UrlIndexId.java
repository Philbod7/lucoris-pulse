package com.lucoris.pulse.core.domain.id;

import java.io.Serializable;
import java.util.Objects;

/**
 * Zusammengesetzter, nur mapping-seitiger Schlüssel der PK-losen Tabelle {@code url_index}:
 * {@code global_event_id} + {@code url} + {@code source_flag}. Erzwingt DB-seitig KEINE
 * Eindeutigkeit (die Tabelle hat bewusst keinen Primary Key/Unique); dient allein Hibernate als
 * Zeilenidentität für den append-only {@code StatelessSession.insert}.
 */
public class UrlIndexId implements Serializable {

    private Long globalEventId;
    private String url;
    private String sourceFlag;

    public UrlIndexId() {
    }

    public UrlIndexId(Long globalEventId, String url, String sourceFlag) {
        this.globalEventId = globalEventId;
        this.url = url;
        this.sourceFlag = sourceFlag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UrlIndexId that)) {
            return false;
        }
        return Objects.equals(globalEventId, that.globalEventId)
                && Objects.equals(url, that.url)
                && Objects.equals(sourceFlag, that.sourceFlag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(globalEventId, url, sourceFlag);
    }
}
