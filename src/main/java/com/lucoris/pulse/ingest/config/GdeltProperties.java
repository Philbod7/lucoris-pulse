package com.lucoris.pulse.ingest.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des GDELT-Abrufs. Präfix {@code lucoris.ingest.gdelt}; alle Werte über
 * Umgebungsvariablen überschreibbar (keine Secrets). Aktiviert via
 * {@code @EnableConfigurationProperties} in {@code IngestConfig} (nur Profil {@code ingest}).
 */
@ConfigurationProperties(prefix = "lucoris.ingest.gdelt")
public class GdeltProperties {

    /** Basis-URL der freien GDELT-V2-Slices (direkter, kostenloser Bezug). */
    private String baseUrl = "http://data.gdeltproject.org/gdeltv2";

    /** Ehrlicher User-Agent (kein Spoofing; siehe Rechtsregel in docs/ingest-and-sources.md). */
    private String userAgent = "lucoris-pulse/0.0.1 (+https://www.gdeltproject.org; news ingest adapter)";

    /** TCP-Connect-Timeout je Abruf. */
    private Duration connectTimeout = Duration.ofSeconds(15);

    /** Gesamt-Timeout je HTTP-Request. */
    private Duration requestTimeout = Duration.ofSeconds(120);

    /** JDBC-Batchgröße des StatelessSession-Firehose. Muss > 0 sein, damit gebatcht wird. */
    private int jdbcBatchSize = 500;

    /**
     * Marktrelevanz-Set: GKG-Themen-Präfixe (z.B. {@code ECON_}, {@code EPU_}). Ein GKG-Artikel
     * wird nur gespeichert, wenn mindestens eine seiner Themen mit einem dieser Präfixe beginnt
     * (nicht-leerer Mengen-Schnitt). Leeres Set = kein Artikel gilt als relevant.
     */
    private List<String> marketRelevantThemePrefixes = List.of("ECONxx_", "EPUxx_");

    /**
     * Wenn {@code true}, wird am Ende eines Tageslaufs eine Statistik ALLER vorgekommenen
     * GKG-Themen-Codes (Häufigkeit + Marktrelevanz-Markierung) geloggt — Diagnose zum Kuratieren
     * des Marktrelevanz-Sets. Für den Produktivbetrieb abschaltbar.
     */
    private boolean logThemeHistogram = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getJdbcBatchSize() {
        return jdbcBatchSize;
    }

    public void setJdbcBatchSize(int jdbcBatchSize) {
        this.jdbcBatchSize = jdbcBatchSize;
    }

    public List<String> getMarketRelevantThemePrefixes() {
        return marketRelevantThemePrefixes;
    }

    public void setMarketRelevantThemePrefixes(List<String> marketRelevantThemePrefixes) {
        this.marketRelevantThemePrefixes = marketRelevantThemePrefixes;
    }

    public boolean isLogThemeHistogram() {
        return logThemeHistogram;
    }

    public void setLogThemeHistogram(boolean logThemeHistogram) {
        this.logThemeHistogram = logThemeHistogram;
    }
}
