package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.UrlIndexId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Reiner, append-only URL&lt;-&gt;Event-Index (Tabelle {@code url_index}). Bildet jede eingelesene
 * Quell-URL auf ihre {@code global_event_id} ab, damit eine (z.B. von Perplexity gelieferte) URL,
 * die wegen robots.txt/TDM-Vorbehalt nicht gelesen werden darf, auf ihr Ereignis abgebildet und
 * darüber ALLE anderen Quell-URLs desselben Events gefunden werden können (Fact-Check über
 * alternative Quellen).
 *
 * <p>Die Tabelle hat BEWUSST keinen Primary Key / kein Unique — Dubletten sind erlaubt (kein
 * Konfliktprüf-/Dedup-Aufwand am Firehose). Die drei fachlichen Spalten bilden nur mapping-seitig
 * (via {@link UrlIndexId}) die Identität; da {@code StatelessSession.insert} keine Identity-Map hat
 * und kein SELECT-before-insert macht, entstehen bei gleichen Werten schlicht mehrere Zeilen.
 *
 * <p>{@code source_flag}: {@code 'P'} = primär ({@code gdelt_events.source_url}),
 * {@code 'S'} = sekundär ({@code gdelt_mentions.mention_identifier}). Bewusst 1 Zeichen und
 * erweiterbar.
 *
 * @see UrlIndexId
 */
@Entity
@Table(name = "url_index")
@IdClass(UrlIndexId.class)
public class UrlIndex {

    /** Ereignis-Klammer (aus {@code GdeltEvent} bzw. {@code GdeltMention}). */
    @Id
    @Column(name = "global_event_id")
    private Long globalEventId;

    /** Artikel-URL (Lookup-Ziel). */
    @Id
    @Column(name = "url")
    private String url;

    /**
     * Quellen-Flag, 1 Zeichen: {@code "P"} = primär (Event), {@code "S"} = sekundär (Mention).
     * Als {@code String} gemappt, aber per {@link JdbcTypeCode} auf {@code CHAR} + Länge 1 fixiert,
     * damit {@code hbm2ddl validate} gegen die DB-Spalte {@code char(1)} (bpchar) passt.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_flag", length = 1)
    private String sourceFlag;

    public UrlIndex() {
    }

    public UrlIndex(Long globalEventId, String url, String sourceFlag) {
        this.globalEventId = globalEventId;
        this.url = url;
        this.sourceFlag = sourceFlag;
    }

    public Long getGlobalEventId() {
        return globalEventId;
    }

    public void setGlobalEventId(Long globalEventId) {
        this.globalEventId = globalEventId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSourceFlag() {
        return sourceFlag;
    }

    public void setSourceFlag(String sourceFlag) {
        this.sourceFlag = sourceFlag;
    }
}
