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

    /** Der periodische Poller ({@code lucoris.ingest.primary.poll.*}). */
    private final Poll poll = new Poll();

    /** Der EDGAR-Handler ({@code lucoris.ingest.primary.sec-edgar.*}). */
    private final SecEdgar secEdgar = new SecEdgar();

    /** Konfiguration der beiden EDGAR-Handler. */
    public static class SecEdgar {

        /** Classpath-Ort der kuratierten CIK-Watchlist — ohne {@code classpath:}-Präfix. */
        private String ciks = "primary-sources/sec-edgar-ciks.json";

        /**
         * Mindestabstand zwischen zwei Requests an EDGAR. Die SEC lässt 10 Req/s pro IP zu und
         * drosselt darüber mit 403; 120 ms (~8 Req/s) hält bewusst Abstand zur Grenze, statt sie
         * auszureizen. Ein Sweep über 90 CIKs dauert damit ~11 s — bei 120 s Poll-Intervall reichlich.
         */
        private Duration pacing = Duration.ofMillis(120);

        /**
         * Wie weit zurück Einreichungen noch als Meldung gelten. Nötig, weil {@code filings.recent}
         * bis zu ~1000 Einträge (rund ein Jahr) führt: ohne Fenster würde jeder Tick die ganze
         * Historie jeder Firma durch die Dedup-Prüfung schicken. Großzügig genug, um einen
         * mehrtägigen Ausfall aufzuholen.
         */
        private Duration lookback = Duration.ofDays(7);

        /**
         * Wie viele Tagesindizes der Voll-Abgleich ({@code sec_edgar_daily}) je Lauf liest.
         *
         * <p>Nicht 1: die Datei des laufenden Tages erscheint erst gegen 22:00 ET (davor antwortet
         * die SEC mit 403). Läse der Adapter nur „heute", lieferte er nur zwischen 22:00 ET und
         * Mitternacht überhaupt etwas — ein Neustart in diesem Fenster verlöre den Tag still. Genau
         * das darf ein Sicherheitsnetz nicht. 3 Tage überbrücken zudem ein Wochenende. Die
         * Überlappung kostet nichts: {@code DedupKeys} kollabiert alles bereits Gespeicherte.
         */
        private int dailyIndexDays = 3;

        public String getCiks() {
            return ciks;
        }

        public void setCiks(String ciks) {
            this.ciks = ciks;
        }

        public Duration getPacing() {
            return pacing;
        }

        public void setPacing(Duration pacing) {
            this.pacing = pacing;
        }

        public Duration getLookback() {
            return lookback;
        }

        public void setLookback(Duration lookback) {
            this.lookback = lookback;
        }

        public int getDailyIndexDays() {
            return dailyIndexDays;
        }

        public void setDailyIndexDays(int dailyIndexDays) {
            this.dailyIndexDays = dailyIndexDays;
        }
    }

    /** Konfiguration des Pollers. Default AUS — Tests dürfen nie von selbst ins Netz. */
    public static class Poll {

        /** Schaltet den {@code @Scheduled}-Poller frei. Default {@code false} (auch für Tests). */
        private boolean enabled = false;

        /**
         * Abstand zwischen zwei Poll-Ticks (fixedDelay). Der Tick prüft nur die Fälligkeit —
         * WIE OFT eine Quelle tatsächlich abgerufen wird, bestimmt ihr Manifest-Intervall.
         */
        private Duration tickInterval = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTickInterval() {
            return tickInterval;
        }

        public void setTickInterval(Duration tickInterval) {
            this.tickInterval = tickInterval;
        }
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

    public Poll getPoll() {
        return poll;
    }

    public SecEdgar getSecEdgar() {
        return secEdgar;
    }
}
