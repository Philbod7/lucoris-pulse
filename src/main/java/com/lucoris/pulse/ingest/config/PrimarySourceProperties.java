package com.lucoris.pulse.ingest.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguration des Primärquellen-Ingests. Präfix {@code lucoris.ingest.primary}; alle Werte über
 * Umgebungsvariablen überschreibbar (keine Secrets). Aktiviert via
 * {@code @EnableConfigurationProperties} in {@code PrimarySourcesConfig}.
 */
@ConfigurationProperties(prefix = "lucoris.ingest.primary")
public class PrimarySourceProperties {

    /** Ehrlicher Bot-User-Agent mit Kontaktseite (kein Spoofing; docs/ingest-and-sources.md). */
    private String userAgent = "LucorisBot/0.1 (+https://www.lucoris.com/bot)";

    /** TCP-Connect-Timeout je Feed-Abruf. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Gesamt-Timeout je HTTP-Request. */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Classpath-Ort des Routing-Manifests — ohne {@code classpath:}-Präfix, ohne führenden Schrägstrich. */
    private String manifest = "primary-sources/lucoris-pulse-primary-sources.json";

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

    public String getManifest() {
        return manifest;
    }

    public void setManifest(String manifest) {
        this.manifest = manifest;
    }
}
