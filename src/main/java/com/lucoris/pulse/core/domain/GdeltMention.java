package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.MentionId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * GDELT-Erwähnung (Roh-Schicht A). Ein Ereignis ({@link GdeltEvent}) hat 1:N Erwähnungen.
 * Monatlich RANGE-partitioniert über {@code mention_time_date}; der zusammengesetzte
 * Primärschlüssel kombiniert das Surrogat {@code mention_id} (aus {@code mention_seq}) mit dem
 * Partitionsschlüssel.
 *
 * @see MentionId
 */
@Entity
@Table(name = "gdelt_mentions")
@IdClass(MentionId.class)
public class GdeltMention {

    /** Surrogat aus {@code mention_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mention_generator")
    @SequenceGenerator(name = "mention_generator", sequenceName = "mention_seq", allocationSize = 50)
    @Column(name = "mention_id")
    private Long mentionId;

    /** Zeitpunkt dieser Erwähnung (Partitionsschlüssel). */
    @Id
    @Column(name = "mention_time_date")
    private Instant mentionTimeDate;

    /** Verweis auf das Ereignis (Klammer über Erwähnungen). */
    @Column(name = "global_event_id", nullable = false)
    private Long globalEventId;

    /** Zeitpunkt des zugrunde liegenden Ereignisses. */
    @Column(name = "event_time_date")
    private Instant eventTimeDate;

    /** Quellentyp der Erwähnung (kodiert). */
    @Column(name = "mention_type")
    private Short mentionType;

    /** Name der erwähnenden Quelle. */
    @Column(name = "mention_source_name")
    private String mentionSourceName;

    /** Artikel-URL dieser Erwähnung (Join-Schlüssel zu GKG/article). */
    @Column(name = "mention_identifier")
    private String mentionIdentifier;

    /** Satznummer, in der das Ereignis auftaucht. */
    @Column(name = "sentence_id")
    private Integer sentenceId;

    /** GDELT-Konfidenz der Extraktion (Prozent). */
    @Column(name = "confidence")
    private Short confidence;

    /** Tonwert des erwähnenden Dokuments. */
    @Column(name = "mention_doc_tone")
    private BigDecimal mentionDocTone;

    public Long getMentionId() {
        return mentionId;
    }

    public void setMentionId(Long mentionId) {
        this.mentionId = mentionId;
    }

    public Instant getMentionTimeDate() {
        return mentionTimeDate;
    }

    public void setMentionTimeDate(Instant mentionTimeDate) {
        this.mentionTimeDate = mentionTimeDate;
    }

    public Long getGlobalEventId() {
        return globalEventId;
    }

    public void setGlobalEventId(Long globalEventId) {
        this.globalEventId = globalEventId;
    }

    public Instant getEventTimeDate() {
        return eventTimeDate;
    }

    public void setEventTimeDate(Instant eventTimeDate) {
        this.eventTimeDate = eventTimeDate;
    }

    public Short getMentionType() {
        return mentionType;
    }

    public void setMentionType(Short mentionType) {
        this.mentionType = mentionType;
    }

    public String getMentionSourceName() {
        return mentionSourceName;
    }

    public void setMentionSourceName(String mentionSourceName) {
        this.mentionSourceName = mentionSourceName;
    }

    public String getMentionIdentifier() {
        return mentionIdentifier;
    }

    public void setMentionIdentifier(String mentionIdentifier) {
        this.mentionIdentifier = mentionIdentifier;
    }

    public Integer getSentenceId() {
        return sentenceId;
    }

    public void setSentenceId(Integer sentenceId) {
        this.sentenceId = sentenceId;
    }

    public Short getConfidence() {
        return confidence;
    }

    public void setConfidence(Short confidence) {
        this.confidence = confidence;
    }

    public BigDecimal getMentionDocTone() {
        return mentionDocTone;
    }

    public void setMentionDocTone(BigDecimal mentionDocTone) {
        this.mentionDocTone = mentionDocTone;
    }
}
