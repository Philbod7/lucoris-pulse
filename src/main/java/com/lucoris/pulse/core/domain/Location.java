package com.lucoris.pulse.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Ort (Entitäts-Schicht C). GDELT geokodiert Orte (FeatureID/FIPS/ADM/LatLong) — nahezu
 * kanonisch. Die Auflösung geschieht beim Ingest über die natürliche Geo-Identität
 * ({@code location_type}, {@code full_name}, {@code country_code}, {@code adm1_code}); der
 * Primärschlüssel ist dennoch ein Surrogat aus {@code location_seq}.
 */
@Entity
@Table(name = "location")
public class Location {

    /** Surrogat aus {@code location_seq} (Hibernate: pooled-lo, allocationSize 50). */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "location_generator")
    @SequenceGenerator(name = "location_generator", sequenceName = "location_seq", allocationSize = 50)
    @Column(name = "location_id")
    private Long locationId;

    /** GDELT/GeoNames-FeatureID (geografischer Identifikator). */
    @Column(name = "feature_id")
    private String featureId;

    /** Auflösungstyp (Land, Bundesstaat, Stadt ...). */
    @Column(name = "location_type")
    private Short locationType;

    /** Voller Ortsname. */
    @Column(name = "full_name")
    private String fullName;

    /** FIPS-Ländercode. */
    @Column(name = "country_code")
    private String countryCode;

    /** ADM1-Verwaltungscode. */
    @Column(name = "adm1_code")
    private String adm1Code;

    /** Breitengrad. */
    @Column(name = "latitude")
    private BigDecimal latitude;

    /** Längengrad. */
    @Column(name = "longitude")
    private BigDecimal longitude;

    public Long getLocationId() {
        return locationId;
    }

    public void setLocationId(Long locationId) {
        this.locationId = locationId;
    }

    public String getFeatureId() {
        return featureId;
    }

    public void setFeatureId(String featureId) {
        this.featureId = featureId;
    }

    public Short getLocationType() {
        return locationType;
    }

    public void setLocationType(Short locationType) {
        this.locationType = locationType;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String countryCode) {
        this.countryCode = countryCode;
    }

    public String getAdm1Code() {
        return adm1Code;
    }

    public void setAdm1Code(String adm1Code) {
        this.adm1Code = adm1Code;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }
}
