package com.lucoris.pulse.core.domain;

import com.lucoris.pulse.core.domain.id.GkgId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * GDELT-GKG-Datensatz (Roh-Schicht A, artikel-zentriert). Enthält die semikolon-getrennten
 * Rohlisten (Themen/Orte/Personen/Organisationen), die NUR hier leben und beim Ingest aufgelöst
 * werden. Monatlich RANGE-partitioniert über {@code seen_date}; zusammengesetzter, natürlicher
 * Primärschlüssel ({@code gkg_record_id}, {@code seen_date}). GKG hat KEINE {@code global_event_id}
 * — die Brücke zu Mentions läuft über die URL ({@code document_identifier}).
 *
 * @see GkgId
 */
@Entity
@Table(name = "gdelt_gkg")
@IdClass(GkgId.class)
public class GdeltGkg {

    /** Eindeutige GKG-Datensatz-ID. */
    @Id
    @Column(name = "gkg_record_id")
    private String gkgRecordId;

    /** Erfassungszeitpunkt (V2.1DATE, Partitionsschlüssel). */
    @Id
    @Column(name = "seen_date")
    private Instant seenDate;

    /** Quellenname (Domain/Publikation). */
    @Column(name = "source_common_name")
    private String sourceCommonName;

    /** Artikel-URL (Join-Schlüssel zu Mentions/article). */
    @Column(name = "document_identifier")
    private String documentIdentifier;

    /** Rohliste Themen (';'-getrennt) — nur Fidelity, wird aufgelöst. */
    @Column(name = "v2_themes")
    private String v2Themes;

    /** Rohliste Orte (geokodiert, '#'/';'-Felder) — wird aufgelöst. */
    @Column(name = "v2_locations")
    private String v2Locations;

    /** Rohliste Personen (';'-getrennt) — wird aufgelöst. */
    @Column(name = "v2_persons")
    private String v2Persons;

    /** Rohliste Organisationen (';'-getrennt) — wird aufgelöst. */
    @Column(name = "v2_organizations")
    private String v2Organizations;

    /** Rohliste aller Eigennamen. */
    @Column(name = "v2_all_names")
    private String v2AllNames;

    /** Roh-Tontupel (mehrere Kennzahlen). */
    @Column(name = "v2_tone")
    private String v2Tone;

    /** Geparster Haupttonwert (erstes Feld aus {@code v2_tone}). */
    @Column(name = "tone")
    private BigDecimal tone;

    public String getGkgRecordId() {
        return gkgRecordId;
    }

    public void setGkgRecordId(String gkgRecordId) {
        this.gkgRecordId = gkgRecordId;
    }

    public Instant getSeenDate() {
        return seenDate;
    }

    public void setSeenDate(Instant seenDate) {
        this.seenDate = seenDate;
    }

    public String getSourceCommonName() {
        return sourceCommonName;
    }

    public void setSourceCommonName(String sourceCommonName) {
        this.sourceCommonName = sourceCommonName;
    }

    public String getDocumentIdentifier() {
        return documentIdentifier;
    }

    public void setDocumentIdentifier(String documentIdentifier) {
        this.documentIdentifier = documentIdentifier;
    }

    public String getV2Themes() {
        return v2Themes;
    }

    public void setV2Themes(String v2Themes) {
        this.v2Themes = v2Themes;
    }

    public String getV2Locations() {
        return v2Locations;
    }

    public void setV2Locations(String v2Locations) {
        this.v2Locations = v2Locations;
    }

    public String getV2Persons() {
        return v2Persons;
    }

    public void setV2Persons(String v2Persons) {
        this.v2Persons = v2Persons;
    }

    public String getV2Organizations() {
        return v2Organizations;
    }

    public void setV2Organizations(String v2Organizations) {
        this.v2Organizations = v2Organizations;
    }

    public String getV2AllNames() {
        return v2AllNames;
    }

    public void setV2AllNames(String v2AllNames) {
        this.v2AllNames = v2AllNames;
    }

    public String getV2Tone() {
        return v2Tone;
    }

    public void setV2Tone(String v2Tone) {
        this.v2Tone = v2Tone;
    }

    public BigDecimal getTone() {
        return tone;
    }

    public void setTone(BigDecimal tone) {
        this.tone = tone;
    }
}
