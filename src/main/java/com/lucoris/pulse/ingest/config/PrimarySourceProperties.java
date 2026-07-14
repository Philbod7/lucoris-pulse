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
    private String userAgent = "LucorisNewsBot/1.0 (+https://lucoris.com/bot)";

    /** TCP-Connect-Timeout je Feed-Abruf. */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Gesamt-Timeout je HTTP-Request. */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /** Classpath-Ort des Routing-Manifests — ohne {@code classpath:}-Präfix, ohne führenden Schrägstrich. */
    private String manifest = "primary-sources/lucoris-pulse-primary-sources.json";

    /**
     * Haltedauer einer erfolgreich geholten robots.txt-/TDM-Auskunft. Ohne Cache würde robots.txt
     * bei 300s-Poll 288-mal am Tag je Quelle geholt.
     */
    private Duration robotsSuccessTtl = Duration.ofHours(24);

    /**
     * Haltedauer einer GESCHEITERTEN Auskunft. Bewusst kurz: fail-closed sperrt die Quelle, solange
     * die Auskunft fehlt — ein kurzer Serverfehler darf sie nicht für einen ganzen Tag aussperren.
     */
    private Duration robotsFailureTtl = Duration.ofMinutes(5);

    /** Obergrenze des robots-Caches (Hosts). Pflicht — kein unbegrenzter Cache. */
    private int robotsCacheMaxHosts = 500;

    /**
     * Ab wann eine in der Registry festgestellte {@code express_invitation} als veraltet gilt
     * (ADR 24). Evidenz altert: eine Einladung, die 2026 gelesen wurde, trägt einen Abruf 2031
     * nicht mehr. Danach: {@code BLOCKED_STALE_INVITATION}, bis jemand sie erneut feststellt.
     */
    private Duration invitationMaxAge = Duration.ofDays(180);

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

    public Duration getRobotsSuccessTtl() {
        return robotsSuccessTtl;
    }

    public void setRobotsSuccessTtl(Duration robotsSuccessTtl) {
        this.robotsSuccessTtl = robotsSuccessTtl;
    }

    public Duration getRobotsFailureTtl() {
        return robotsFailureTtl;
    }

    public void setRobotsFailureTtl(Duration robotsFailureTtl) {
        this.robotsFailureTtl = robotsFailureTtl;
    }

    public int getRobotsCacheMaxHosts() {
        return robotsCacheMaxHosts;
    }

    public void setRobotsCacheMaxHosts(int robotsCacheMaxHosts) {
        this.robotsCacheMaxHosts = robotsCacheMaxHosts;
    }

    public Duration getInvitationMaxAge() {
        return invitationMaxAge;
    }

    public void setInvitationMaxAge(Duration invitationMaxAge) {
        this.invitationMaxAge = invitationMaxAge;
    }
}
