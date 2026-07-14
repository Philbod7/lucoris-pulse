package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Eine Feed-Meldung (RSS/Atom-Item) aus einer Primärquelle, quellenübergreifend dedupliziert
 * über {@code dedup_key}. Bewusst nicht „Event": das reale Ereignis wird später von einer
 * Resolver-Entität abgebildet, die Meldungen clustert und über {@code url_index} mit GDELT
 * verbindet.
 *
 * <p>Geschrieben ausschließlich über den Firehose-Pfad ({@code StatelessSession}); die
 * Attribution liegt flach in drei Spalten, damit das Read-Model ohne Sondertypen abfragen kann.
 */
@Entity
@Table(name = "primary_feed_item")
public class PrimaryFeedItem {

    /** Surrogat aus {@code primary_feed_item_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "primary_feed_item_generator")
    @SequenceGenerator(
            name = "primary_feed_item_generator",
            sequenceName = "primary_feed_item_seq",
            allocationSize = 50)
    @Column(name = "primary_feed_item_id")
    private Long primaryFeedItemId;

    /** Dedup-Anker: URL-förmige guid normalisiert, sonst normalisierter Link (s. {@code DedupKeys}). */
    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    /** Manifest-id der ERSTEN Quelle, die die Meldung lieferte. */
    @Column(name = "source_id", nullable = false)
    private String sourceId;

    /** Klarname des Herausgebers (Quellzeile). */
    @Column(name = "institution", nullable = false)
    private String institution;

    /** Titel des Eintrags; kann fehlen. */
    @Column(name = "title")
    private String title;

    /** Deep-Link, roh und unnormalisiert (Join-Anker für den späteren url_index-Resolver). */
    @Column(name = "url", nullable = false)
    private String url;

    /** Roh-guid aus dem Feed; Audit-Spur der Schlüsselberechnung, erlaubt Re-Keying. */
    @Column(name = "guid")
    private String guid;

    /** Veröffentlichungszeitpunkt (UTC-normalisiert). */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /** Rohtext aus {@code content:encoded} bzw. {@code description}/{@code summary}. */
    @Column(name = "raw_summary")
    private String rawSummary;

    /** Sprache laut Feed. */
    @Column(name = "language")
    private String language;

    /** Zeitpunkt unseres Abrufs. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** {@code A} oder {@code B}, aus der Quelle durchgereicht. */
    @Column(name = "legal_class", nullable = false)
    private String legalClass;

    /** Attributionspflicht der Quelle. */
    @Column(name = "attribution_required", nullable = false)
    private boolean attributionRequired;

    /** Vorgegebene Attributionsformel; {@code null} = keine. */
    @Column(name = "attribution_formula")
    private String attributionFormula;

    /** Veränderungshinweis nötig (dl-de/by-2.0). */
    @Column(name = "attribution_modified_note", nullable = false)
    private boolean attributionModifiedNote;

    public Long getPrimaryFeedItemId() {
        return primaryFeedItemId;
    }

    public void setPrimaryFeedItemId(Long primaryFeedItemId) {
        this.primaryFeedItemId = primaryFeedItemId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getInstitution() {
        return institution;
    }

    public void setInstitution(String institution) {
        this.institution = institution;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getGuid() {
        return guid;
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getRawSummary() {
        return rawSummary;
    }

    public void setRawSummary(String rawSummary) {
        this.rawSummary = rawSummary;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getLegalClass() {
        return legalClass;
    }

    public void setLegalClass(String legalClass) {
        this.legalClass = legalClass;
    }

    public boolean isAttributionRequired() {
        return attributionRequired;
    }

    public void setAttributionRequired(boolean attributionRequired) {
        this.attributionRequired = attributionRequired;
    }

    public String getAttributionFormula() {
        return attributionFormula;
    }

    public void setAttributionFormula(String attributionFormula) {
        this.attributionFormula = attributionFormula;
    }

    public boolean isAttributionModifiedNote() {
        return attributionModifiedNote;
    }

    public void setAttributionModifiedNote(boolean attributionModifiedNote) {
        this.attributionModifiedNote = attributionModifiedNote;
    }
}
