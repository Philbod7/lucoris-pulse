package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * GDELT-Ereignis (Roh-Schicht A, 1:1-Abbild). {@code global_event_id} ist global eindeutig
 * (von GDELT vergeben, KEINE Sequence) und bildet die Klammer über alle Erwähnungen
 * ({@link GdeltMention}) desselben Ereignisses.
 */
@Entity
@Table(name = "gdelt_events")
public class GdeltEvent {

    /** Global eindeutige Ereignis-ID (Klammer über alle Erwähnungen). */
    @Id
    @Column(name = "global_event_id")
    private Long globalEventId;

    /** Erst-Sichtung durch GDELT (15-Min-Raster, DATEADDED). */
    @Column(name = "date_added", nullable = false)
    private Instant dateAdded;

    /** Ereignisdatum (SQLDATE). */
    @Column(name = "day")
    private LocalDate day;

    /** CAMEO-Code des ersten Akteurs. */
    @Column(name = "actor1_code")
    private String actor1Code;

    /** Klarname des ersten Akteurs. */
    @Column(name = "actor1_name")
    private String actor1Name;

    /** Herkunftsland des ersten Akteurs. */
    @Column(name = "actor1_country_code")
    private String actor1CountryCode;

    /** Akteurstyp des ersten Akteurs (z.B. GOV, BUS). */
    @Column(name = "actor1_type1_code")
    private String actor1Type1Code;

    /** CAMEO-Code des zweiten Akteurs. */
    @Column(name = "actor2_code")
    private String actor2Code;

    /** Klarname des zweiten Akteurs. */
    @Column(name = "actor2_name")
    private String actor2Name;

    /** Herkunftsland des zweiten Akteurs. */
    @Column(name = "actor2_country_code")
    private String actor2CountryCode;

    /** Akteurstyp des zweiten Akteurs. */
    @Column(name = "actor2_type1_code")
    private String actor2Type1Code;

    /** Kennzeichen, ob Kernereignis der Meldung. */
    @Column(name = "is_root_event")
    private Boolean rootEvent;

    /** CAMEO-Ereigniscode (feinste Ebene). */
    @Column(name = "event_code")
    private String eventCode;

    /** CAMEO-Basiscode (mittlere Ebene). */
    @Column(name = "event_base_code")
    private String eventBaseCode;

    /** CAMEO-Wurzelcode (grobe Kategorie). */
    @Column(name = "event_root_code")
    private String eventRootCode;

    /** Konfliktklasse: 1 verb.Koop · 2 mat.Koop · 3 verb.Konflikt · 4 mat.Konflikt. */
    @Column(name = "quad_class")
    private Short quadClass;

    /** Goldstein-Skala: Stabilitätseinfluss (-10..+10). */
    @Column(name = "goldstein_scale")
    private BigDecimal goldsteinScale;

    /** Anzahl Erwähnungen. */
    @Column(name = "num_mentions")
    private Integer numMentions;

    /** Anzahl unterschiedlicher Quellen. */
    @Column(name = "num_sources")
    private Integer numSources;

    /** Anzahl abdeckender Artikel. */
    @Column(name = "num_articles")
    private Integer numArticles;

    /** Durchschnittlicher Tonwert. */
    @Column(name = "avg_tone")
    private BigDecimal avgTone;

    /** Geo-Auflösungstyp des Ereignisorts. */
    @Column(name = "action_geo_type")
    private Short actionGeoType;

    /** Voller Ortsname des Ereignisorts. */
    @Column(name = "action_geo_fullname")
    private String actionGeoFullname;

    /** Ländercode des Ereignisorts. */
    @Column(name = "action_geo_country_code")
    private String actionGeoCountryCode;

    /** Breitengrad des Ereignisorts. */
    @Column(name = "action_geo_lat")
    private BigDecimal actionGeoLat;

    /** Längengrad des Ereignisorts. */
    @Column(name = "action_geo_long")
    private BigDecimal actionGeoLong;

    /** URL des repräsentativen Quellartikels (nur EINE pro Ereignis). */
    @Column(name = "source_url")
    private String sourceUrl;

    public Long getGlobalEventId() {
        return globalEventId;
    }

    public void setGlobalEventId(Long globalEventId) {
        this.globalEventId = globalEventId;
    }

    public Instant getDateAdded() {
        return dateAdded;
    }

    public void setDateAdded(Instant dateAdded) {
        this.dateAdded = dateAdded;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public String getActor1Code() {
        return actor1Code;
    }

    public void setActor1Code(String actor1Code) {
        this.actor1Code = actor1Code;
    }

    public String getActor1Name() {
        return actor1Name;
    }

    public void setActor1Name(String actor1Name) {
        this.actor1Name = actor1Name;
    }

    public String getActor1CountryCode() {
        return actor1CountryCode;
    }

    public void setActor1CountryCode(String actor1CountryCode) {
        this.actor1CountryCode = actor1CountryCode;
    }

    public String getActor1Type1Code() {
        return actor1Type1Code;
    }

    public void setActor1Type1Code(String actor1Type1Code) {
        this.actor1Type1Code = actor1Type1Code;
    }

    public String getActor2Code() {
        return actor2Code;
    }

    public void setActor2Code(String actor2Code) {
        this.actor2Code = actor2Code;
    }

    public String getActor2Name() {
        return actor2Name;
    }

    public void setActor2Name(String actor2Name) {
        this.actor2Name = actor2Name;
    }

    public String getActor2CountryCode() {
        return actor2CountryCode;
    }

    public void setActor2CountryCode(String actor2CountryCode) {
        this.actor2CountryCode = actor2CountryCode;
    }

    public String getActor2Type1Code() {
        return actor2Type1Code;
    }

    public void setActor2Type1Code(String actor2Type1Code) {
        this.actor2Type1Code = actor2Type1Code;
    }

    public Boolean getRootEvent() {
        return rootEvent;
    }

    public void setRootEvent(Boolean rootEvent) {
        this.rootEvent = rootEvent;
    }

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public String getEventBaseCode() {
        return eventBaseCode;
    }

    public void setEventBaseCode(String eventBaseCode) {
        this.eventBaseCode = eventBaseCode;
    }

    public String getEventRootCode() {
        return eventRootCode;
    }

    public void setEventRootCode(String eventRootCode) {
        this.eventRootCode = eventRootCode;
    }

    public Short getQuadClass() {
        return quadClass;
    }

    public void setQuadClass(Short quadClass) {
        this.quadClass = quadClass;
    }

    public BigDecimal getGoldsteinScale() {
        return goldsteinScale;
    }

    public void setGoldsteinScale(BigDecimal goldsteinScale) {
        this.goldsteinScale = goldsteinScale;
    }

    public Integer getNumMentions() {
        return numMentions;
    }

    public void setNumMentions(Integer numMentions) {
        this.numMentions = numMentions;
    }

    public Integer getNumSources() {
        return numSources;
    }

    public void setNumSources(Integer numSources) {
        this.numSources = numSources;
    }

    public Integer getNumArticles() {
        return numArticles;
    }

    public void setNumArticles(Integer numArticles) {
        this.numArticles = numArticles;
    }

    public BigDecimal getAvgTone() {
        return avgTone;
    }

    public void setAvgTone(BigDecimal avgTone) {
        this.avgTone = avgTone;
    }

    public Short getActionGeoType() {
        return actionGeoType;
    }

    public void setActionGeoType(Short actionGeoType) {
        this.actionGeoType = actionGeoType;
    }

    public String getActionGeoFullname() {
        return actionGeoFullname;
    }

    public void setActionGeoFullname(String actionGeoFullname) {
        this.actionGeoFullname = actionGeoFullname;
    }

    public String getActionGeoCountryCode() {
        return actionGeoCountryCode;
    }

    public void setActionGeoCountryCode(String actionGeoCountryCode) {
        this.actionGeoCountryCode = actionGeoCountryCode;
    }

    public BigDecimal getActionGeoLat() {
        return actionGeoLat;
    }

    public void setActionGeoLat(BigDecimal actionGeoLat) {
        this.actionGeoLat = actionGeoLat;
    }

    public BigDecimal getActionGeoLong() {
        return actionGeoLong;
    }

    public void setActionGeoLong(BigDecimal actionGeoLong) {
        this.actionGeoLong = actionGeoLong;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }
}
