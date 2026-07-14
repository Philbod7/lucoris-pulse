package com.lucoris.pulse.ingest.primary.robots;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link RobotsGate} mit Cache je Host — das eigentliche Sicherheitsnetz.
 *
 * <p><strong>Fail-closed:</strong> lässt sich die Erlaubnis nicht feststellen (robots.txt
 * antwortet mit 5xx/401/403 oder gar nicht), wird der Abruf VERWEIGERT. Kein Nachweis der
 * Erlaubnis = kein Abruf; die Beweislast liegt beim Data Miner. Nur ein sauberes „gibt es nicht"
 * (HTTP 404/410) heißt „keine Einschränkung" (RFC 9309).
 *
 * <p>Drei Gründe führen zum Verbot:
 * <ol>
 *   <li>robots.txt verbietet unserem Bot den Pfad,</li>
 *   <li>robots.txt sperrt einen gängigen KI-Crawler für den Pfad (KONSERVATIVE REGEL aus
 *       {@code CLAUDE.md}: Namenslücke nicht ausnutzen),</li>
 *   <li>{@code /.well-known/tdmrep.json} erklärt für den Pfad einen TDM-Vorbehalt.</li>
 * </ol>
 *
 * <p>Der Cache ist Pflicht, kein Luxus: bei 300 s Poll-Intervall würde robots.txt sonst 288-mal am
 * Tag je Quelle geholt. Erfolge werden lange gehalten, Fehlschläge nur kurz — sonst sperrte ein
 * kurzer Serverfehler die Quelle für einen ganzen Tag aus.
 *
 * <p>POJO ohne Spring-Annotationen: der Cache steckt direkt in Caffeine, nicht hinter Springs
 * {@code @Cacheable} — sonst wäre das Gate ohne Spring-Kontext nicht testbar.
 */
public final class CachingRobotsGate implements RobotsGate {

    private static final Logger log = LoggerFactory.getLogger(CachingRobotsGate.class);

    private final PolicyFetcher fetcher;
    private final ObjectMapper mapper;
    private final String botToken;
    private final Cache<String, SitePolicy> byHost;
    private final Clock clock;
    private final Duration maxInvitationAge;

    /**
     * @param fetcher          holt robots.txt und tdmrep.json
     * @param mapper           Jackson-Mapper für tdmrep.json
     * @param userAgent        unser voller User-Agent; das robots-Produkt-Token wird daraus abgeleitet
     * @param successTtl       Haltedauer einer erfolgreich geholten Auskunft (z.B. 24 h)
     * @param failureTtl       Haltedauer einer gescheiterten Auskunft (kurz, z.B. 5 min)
     * @param maxHosts         Obergrenze des Caches (Pflicht — kein unbegrenzter Cache)
     * @param clock            Uhr für die Alterung der Einladungs-Evidenz (im Test fixierbar)
     * @param maxInvitationAge ab wann eine festgestellte Einladung als veraltet gilt (ADR 24)
     */
    public CachingRobotsGate(
            PolicyFetcher fetcher,
            ObjectMapper mapper,
            String userAgent,
            Duration successTtl,
            Duration failureTtl,
            int maxHosts,
            Clock clock,
            Duration maxInvitationAge) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.botToken = RobotsRules.productToken(Objects.requireNonNull(userAgent, "userAgent"));
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxInvitationAge = Objects.requireNonNull(maxInvitationAge, "maxInvitationAge");
        this.byHost = Caffeine.newBuilder()
                .maximumSize(maxHosts)
                .expireAfter(new PolicyExpiry(successTtl, failureTtl))
                .build();
        log.info("RobotsGate aktiv (fail-closed): bot-token={} success-ttl={} failure-ttl={} "
                        + "max-hosts={} invitation-max-age={}",
                botToken, successTtl, failureTtl, maxHosts, maxInvitationAge);
    }

    @Override
    public Decision check(FetchIntent intent) {
        URI url = intent.url();
        String host = hostKey(url);
        String path = pathOf(url);

        SitePolicy policy = byHost.get(host, key -> load(url));

        if (!policy.reachable()) {
            // Fail-closed: wir konnten die Erlaubnis nicht feststellen. Eine Einladung heilt das
            // NICHT — sie sagt etwas über den Feed, nichts über die Erreichbarkeit der Hausordnung.
            return Decision.deny("robots.txt von " + host + " nicht feststellbar (" + policy.problem()
                    + ") — fail-closed: kein Nachweis der Erlaubnis, kein Abruf");
        }

        // (e) TDM zuerst — dieser Kanal gewinnt IMMER, auch gegen eine Einladung.
        if (policy.tdm().isReservedFor(path)) {
            String policyUrl = policy.tdm().policyFor(path);
            return Decision.deny("TDM-Vorbehalt in /.well-known/tdmrep.json für " + path
                    + (policyUrl == null ? "" : " (Policy: " + policyUrl + ")"));
        }

        // (d) Jeder KI-Crawler, den die Seite NAMENTLICH für diesen Pfad sperrt, gewinnt ebenfalls.
        // Konservative Regel: dass unser Token nicht genannt ist, ist eine Namenslücke — die nutzen
        // wir nicht aus.
        List<String> blockedAi = policy.robots().blockedAiCrawlers(path);
        if (!blockedAi.isEmpty()) {
            return Decision.deny("robots.txt sperrt KI-Crawler " + blockedAi + " für " + path
                    + " — konservative Regel: erkennbarer KI-/TDM-Vorbehalt = kein Abruf");
        }

        // TDM und KI stehen bewusst VOR der Einladungs-Leiter: so kann niemand sie später
        // versehentlich dahinter schieben und (d)/(e) still aushebeln.
        Optional<RobotsRules.Match> treffer = policy.robots().match(botToken, path);
        if (treffer.isEmpty() || treffer.get().allow()) {
            return Decision.allow("robots.txt erlaubt " + botToken + " den Pfad " + path
                    + "; kein KI-/TDM-Vorbehalt");
        }

        return byInvitation(intent, treffer.get(), path);
    }

    @Override
    public Optional<Integer> crawlDelaySeconds(URI url) {
        SitePolicy policy = byHost.get(hostKey(url), key -> load(url));
        return policy.reachable() ? policy.robots().crawlDelaySeconds(botToken) : Optional.empty();
    }

    /**
     * Die Einladungs-Leiter: robots.txt verbietet uns den Pfad — trägt eine ausdrückliche Einladung
     * des Herausgebers den Abruf trotzdem? (ADR 24)
     *
     * <p>Jede Sprosse ist ein eigener Ablehnungsgrund im Klartext, damit die Log-Zeile sagt, WORAN
     * es lag. Reihenfolge von „gar keine Einladung" (der Normalfall) zu den feinen Bedingungen.
     */
    private Decision byInvitation(FetchIntent intent, RobotsRules.Match treffer, String path) {
        String basis = "robots.txt verbietet " + botToken + " den Pfad " + path
                + " (Muster \"" + treffer.pattern() + "\" in Gruppe \"" + treffer.group() + "\")";

        ExpressInvitation einladung = intent.invitation();
        if (einladung == null) {
            return Decision.deny(basis); // Der Normalfall.
        }
        // (a) Nur ein Feed kann „zum Abonnieren angeboten" sein.
        if (!intent.isRss()) {
            return Decision.deny(basis + "; eine Einladung gilt nur für access.type=rss, hier ist es '"
                    + intent.accessType() + "' (a)");
        }
        // (b) Die Evidenz muss vollständig UND frisch sein.
        if (!einladung.complete()) {
            return Decision.stale(basis + "; Einladung unvollständig (page_url/wording/retrieved) (b)");
        }
        if (einladung.olderThan(maxInvitationAge, clock.instant())) {
            return Decision.stale(basis + "; Einladung vom " + einladung.retrieved() + " ist älter als "
                    + maxInvitationAge + " — erneut feststellen (b)");
        }
        // (d) Das treffende Disallow muss aus der '*'-Gruppe stammen. Nennt uns die Seite beim Namen
        // und sperrt uns, ist das eine gezielte Absage — keine Namenslücke.
        if (!RobotsRules.WILDCARD_AGENT.equals(treffer.group())) {
            return Decision.deny(basis + "; das Disallow steht in einer SPEZIFISCHEN UA-Gruppe, nicht in '*' (d)");
        }
        // (c) Das Muster muss den Feed nur beiläufig erfassen.
        if (!PatternScope.isIncidental(treffer.pattern(), path)) {
            return Decision.deny(basis + "; das Muster zielt auf den Feed bzw. den Endpunkt, es erfasst ihn"
                    + " nicht nur beiläufig (c)");
        }

        return Decision.byInvitation(
                basis + "; ABER: ausdrückliche Abo-Einladung des Herausgebers (" + einladung.pageUrl()
                        + ", festgestellt " + einladung.retrieved()
                        + "), und das Muster erfasst den Feed nur beiläufig — ALLOW_BY_INVITATION",
                new InvitationEvidence(
                        intent.sourceId(), path, treffer.pattern(), treffer.group(),
                        einladung.pageUrl(), einladung.wording(), einladung.retrieved(), einladung.scope()));
    }

    /** Holt robots.txt und tdmrep.json der Domain — genau einmal je Cache-Eintrag. */
    private SitePolicy load(URI url) {
        URI robotsUrl = url.resolve("/robots.txt");
        PolicyFetcher.Response robots = fetcher.get(robotsUrl);

        if (robots.unknown()) {
            String problem = robots.status() == PolicyFetcher.Response.NETWORK_ERROR
                    ? "nicht erreichbar"
                    : "HTTP " + robots.status();
            log.warn("robots.txt {} nicht feststellbar ({}) — Quelle wird fail-closed gesperrt",
                    robotsUrl, problem);
            return SitePolicy.unreachable(problem);
        }

        RobotsRules rules = robots.absent()
                ? RobotsRules.unrestricted() // 404: es gibt keine robots.txt -> keine Einschränkung
                : RobotsRules.parse(robots.body());

        // tdmrep.json ist ein ZUSÄTZLICHER Kanal, dessen Fehlen der Normalfall ist. Nur eine
        // gelesene Datei kann einen Vorbehalt erklären; ein Fehler hier hebt die robots-Auskunft
        // nicht auf (anders als bei robots.txt selbst, die es immer geben kann).
        PolicyFetcher.Response tdmrep = fetcher.get(url.resolve("/.well-known/tdmrep.json"));
        TdmReservation tdm = tdmrep.ok()
                ? TdmReservation.parse(mapper, tdmrep.body())
                : TdmReservation.none();

        return SitePolicy.of(rules, tdm);
    }

    /** Cache-Schlüssel: Schema + Host + Port (robots.txt gilt je Origin, nicht je Pfad). */
    private static String hostKey(URI url) {
        return url.getScheme() + "://" + url.getAuthority();
    }

    /** Pfad inkl. Query — genau darauf matchen die robots-Regeln. */
    private static String pathOf(URI url) {
        String path = url.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
    }

    /** Erfolge lange halten, Fehlschläge nur kurz (sonst sperrt ein 503 die Quelle einen Tag lang aus). */
    private record PolicyExpiry(Duration successTtl, Duration failureTtl)
            implements Expiry<String, SitePolicy> {

        @Override
        public long expireAfterCreate(String key, SitePolicy policy, long currentTime) {
            return (policy.reachable() ? successTtl : failureTtl).toNanos();
        }

        @Override
        public long expireAfterUpdate(
                String key, SitePolicy policy, long currentTime, long currentDuration) {
            return expireAfterCreate(key, policy, currentTime);
        }

        @Override
        public long expireAfterRead(
                String key, SitePolicy policy, long currentTime, long currentDuration) {
            return currentDuration; // Lesen verlängert nicht
        }
    }
}
