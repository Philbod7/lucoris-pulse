package com.lucoris.pulse.ingest.primary.robots;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reiner Unit-Test des Sicherheitsnetzes — ohne Spring, Netz oder DB. Der {@link PolicyFetcher} ist
 * ein Fake, der vorgegebene HTTP-Antworten liefert und mitzählt, wie oft er gefragt wurde.
 */
class CachingRobotsGateTest {

    private static final URI FEED = URI.create("https://example.org/rss/press.xml");
    private static final URI ROBOTS = URI.create("https://example.org/robots.txt");
    private static final URI TDMREP = URI.create("https://example.org/.well-known/tdmrep.json");
    private static final String UA = "LucorisNewsBot/1.0 (+https://lucoris.com/bot)";

    /** „Jetzt" — die Einladung unten wurde 2026-07-13 festgestellt, ist also frisch. */
    private static final Clock JETZT =
            Clock.fixed(Instant.parse("2026-07-14T09:00:00Z"), ZoneOffset.UTC);
    private static final Duration HALBES_JAHR = Duration.ofDays(180);

    private final FakePolicyFetcher fetcher = new FakePolicyFetcher();

    private RobotsGate gate() {
        return gate(JETZT);
    }

    private RobotsGate gate(Clock clock) {
        return new CachingRobotsGate(
                fetcher, JsonMapper.builder().build(), UA,
                Duration.ofHours(24), Duration.ofMinutes(5), 100, clock, HALBES_JAHR);
    }

    /** Der Normalfall: eine Quelle OHNE Einladung. */
    private static FetchIntent intent(URI url) {
        return new FetchIntent("test-quelle", url, "rss", null);
    }

    /** Eine gültige, frische Einladung (festgestellt einen Tag vor JETZT). */
    private static ExpressInvitation einladung() {
        return new ExpressInvitation(
                "https://example.org/abo.html",
                "Kopieren Sie den Link der RSS-Datei in Ihren RSS-Reader.",
                "2026-07-13",
                null);
    }

    private static FetchIntent invited(URI url) {
        return new FetchIntent("test-quelle", url, "rss", einladung());
    }

    // --- Der Normalfall ---

    @Test
    void allowsWhenRobotsPermitsAndThereIsNoReservation() {
        fetcher.respond(ROBOTS, 200, "User-agent: *\nDisallow: /intern/\n");
        fetcher.respond(TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(intent(FEED));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).contains("erlaubt");
    }

    @Test
    void missingRobotsTxtMeansNoRestriction() {
        // HTTP 404: es GIBT keine robots.txt -> keine Einschränkung (RFC 9309). Das ist der einzige
        // Fehlerfall, der als Erlaubnis gilt.
        fetcher.respond(ROBOTS, 404, "");
        fetcher.respond(TDMREP, 404, "");

        assertThat(gate().check(intent(FEED)).allowed()).isTrue();
    }

    // --- Die drei Verbotsgründe ---

    @Test
    void deniesWhenRobotsDisallowsOurBot() {
        fetcher.respond(ROBOTS, 200, "User-agent: LucorisNewsBot\nDisallow: /rss/\n");
        fetcher.respond(TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(intent(FEED));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("verbietet").contains("/rss/press.xml");
    }

    @Test
    void deniesWhenTheSiteBlocksAiCrawlersEvenThoughOurBotIsAllowed() {
        // Das Kernstück der konservativen Regel: uns nennt die Seite nicht, GPTBot sperrt sie.
        // Diese Namenslücke nutzen wir NICHT aus.
        fetcher.respond(ROBOTS, 200, """
                User-agent: GPTBot
                Disallow: /

                User-agent: *
                Allow: /
                """);
        fetcher.respond(TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(intent(FEED));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("gptbot").contains("konservative Regel");
    }

    @Test
    void deniesOnTdmReservation() {
        fetcher.respond(ROBOTS, 200, "User-agent: *\nAllow: /\n");
        fetcher.respond(TDMREP, 200, """
                [ { "location": "/", "tdm-reservation": 1, "tdm-policy": "https://example.org/tdm" } ]
                """);

        RobotsGate.Decision decision = gate().check(intent(FEED));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("TDM-Vorbehalt").contains("https://example.org/tdm");
    }

    @Test
    void tdmReservationOnAnUnrelatedPathDoesNotBlockUs() {
        fetcher.respond(ROBOTS, 200, "User-agent: *\nAllow: /\n");
        fetcher.respond(TDMREP, 200, """
                [ { "location": "/shop", "tdm-reservation": 1 } ]
                """);

        assertThat(gate().check(intent(FEED)).allowed()).isTrue();
    }

    // --- Fail-closed ---

    @ParameterizedTest(name = "robots.txt HTTP {0} -> gesperrt")
    @ValueSource(ints = {500, 502, 503, 401, 403, 429, PolicyFetcher.Response.NETWORK_ERROR})
    void deniesWhenPermissionCannotBeEstablished(int status) {
        // Fail-closed: kein Nachweis der Erlaubnis = kein Abruf. Die Beweislast liegt bei uns.
        // 429 gehört dazu (RFC 9309 § 2.3.1.4): wer uns drosselt, hat uns die Hausordnung nicht
        // gezeigt — gerade bei Hosts mit hartem Ratenlimit (SEC EDGAR: 10 Req/s).
        fetcher.respond(ROBOTS, status, "");
        fetcher.respond(TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(intent(FEED));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("fail-closed");
    }

    @ParameterizedTest(name = "robots.txt HTTP {0} (unerwartet) -> gesperrt, NICHT geparst")
    @ValueSource(ints = {400, 204, 302})
    void deniesOnUnexpectedStatusInsteadOfParsingTheErrorBody(int status) {
        // Weder gelesen (200) noch sicher abwesend (404/410): der Körper ist dann KEINE robots.txt.
        // Ihn zu parsen ergäbe leere Regeln — also „keine Regel trifft" = erlaubt. Ein Fehlerkörper
        // darf nie zum Freibrief werden.
        fetcher.respond(ROBOTS, status, "<html>Bad Request</html>");
        fetcher.respond(TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(intent(FEED));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("fail-closed");
    }

    @Test
    void unreachableTdmrepDoesNotOverrideAWorkingRobotsAnswer() {
        // tdmrep.json ist ein ZUSATZkanal, dessen Fehlen der Normalfall ist. Ein 503 dort darf die
        // gültige robots-Auskunft nicht entwerten — sonst wäre fast jede Domain gesperrt.
        fetcher.respond(ROBOTS, 200, "User-agent: *\nAllow: /\n");
        fetcher.respond(TDMREP, 503, "");

        assertThat(gate().check(intent(FEED)).allowed()).isTrue();
    }

    // --- Cache ---

    @Test
    void permissionIsFetchedOncePerHostNotOncePerPoll() {
        // Ohne Cache würde robots.txt bei 300s-Poll 288-mal am Tag je Quelle geholt.
        fetcher.respond(ROBOTS, 200, "User-agent: *\nAllow: /\n");
        fetcher.respond(TDMREP, 404, "");
        RobotsGate gate = gate();

        gate.check(intent(FEED));
        gate.check(intent(URI.create("https://example.org/rss/anderer-feed.xml")));
        gate.check(intent(FEED));

        assertThat(fetcher.callsTo(ROBOTS)).isEqualTo(1);
        assertThat(fetcher.callsTo(TDMREP)).isEqualTo(1);
    }

    @Test
    void differentHostsAreCachedSeparately() {
        fetcher.respond(ROBOTS, 200, "User-agent: *\nAllow: /\n");
        fetcher.respond(TDMREP, 404, "");
        fetcher.respond(URI.create("https://andere.org/robots.txt"), 200, "User-agent: *\nDisallow: /\n");
        fetcher.respond(URI.create("https://andere.org/.well-known/tdmrep.json"), 404, "");
        RobotsGate gate = gate();

        assertThat(gate.check(intent(FEED)).allowed()).isTrue();
        assertThat(gate.check(intent(URI.create("https://andere.org/rss.xml"))).allowed()).isFalse();
    }

    // =========================================================================================
    // ALLOW_BY_INVITATION (ADR 24) — jede der fünf Bedingungen muss EINZELN widerlegbar sein.
    // Grundlage ist überall die ECHTE BMF-robots.txt: '*'-Gruppe mit "Disallow: */SiteGlobals",
    // kein KI-Crawler genannt, kein TDM-Vorbehalt.
    // =========================================================================================

    /** Der echte BMF-Feed — liegt im generisch gesperrten SiteGlobals-Zweig. */
    private static final URI BMF_FEED = URI.create(
            "https://bmf.example/SiteGlobals/Functions/RSSFeed/DE/Pressemitteilungen/RSSPressemitteilungen.xml");
    private static final URI BMF_ROBOTS = URI.create("https://bmf.example/robots.txt");
    private static final URI BMF_TDMREP = URI.create("https://bmf.example/.well-known/tdmrep.json");

    /** Legt die BMF-Lage an: robots.txt aus der echten Aufzeichnung, keine TDM-Datei. */
    private void bmfLage() {
        fetcher.respond(BMF_ROBOTS, 200, fixture("robots/bmf-robots.txt"));
        fetcher.respond(BMF_TDMREP, 404, "");
    }

    @Test
    void invitationCarriesAGenericDisallow() {
        // DER Anlassfall. Ohne Einladung wäre das BLOCKED (so war es bis ADR 24).
        bmfLage();

        RobotsGate.Decision decision = gate().check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.ALLOW_BY_INVITATION);
        assertThat(decision.allowed()).isTrue();

        // Die Beweislast-Evidenz muss vollständig am Ergebnis hängen.
        RobotsGate.InvitationEvidence e = decision.evidence();
        assertThat(e).isNotNull();
        assertThat(e.sourceId()).isEqualTo("test-quelle");
        assertThat(e.pattern()).isEqualTo("*/SiteGlobals");
        assertThat(e.userAgentGroup()).isEqualTo("*");
        assertThat(e.pageUrl()).isEqualTo("https://example.org/abo.html");
        assertThat(e.retrieved()).isEqualTo("2026-07-13");
        assertThat(e.wording()).contains("RSS-Reader");
    }

    @Test
    void withoutAnInvitationTheSameSourceStaysBlocked() {
        // Die Gegenprobe: es ist die EVIDENZ, die trägt — nicht das Muster allein.
        bmfLage();

        RobotsGate.Decision decision = gate().check(intent(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.evidence()).isNull();
    }

    @Test
    void conditionA_invitationOnlyAppliesToRssFeeds() {
        bmfLage();
        FetchIntent api = new FetchIntent("test-quelle", BMF_FEED, "api", einladung());

        RobotsGate.Decision decision = gate().check(api);

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("(a)");
    }

    @Test
    void conditionB_incompleteEvidenceDoesNotCarry() {
        bmfLage();
        ExpressInvitation ohneWording =
                new ExpressInvitation("https://example.org/abo.html", null, "2026-07-13", null);

        RobotsGate.Decision decision =
                gate().check(new FetchIntent("test-quelle", BMF_FEED, "rss", ohneWording));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED_STALE_INVITATION);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).contains("(b)");
    }

    @Test
    void conditionB_evidenceGoesStale() {
        // Eine Einladung von 2026 trägt einen Abruf 2027 nicht mehr. Kein Dauerfreifahrtschein.
        bmfLage();
        Clock spaeter = Clock.fixed(Instant.parse("2027-07-14T09:00:00Z"), ZoneOffset.UTC);

        RobotsGate.Decision decision = gate(spaeter).check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED_STALE_INVITATION);
        assertThat(decision.reason()).contains("älter als").contains("2026-07-13");
    }

    @ParameterizedTest(name = "[{index}] gezieltes Muster {0} -> keine Einladung")
    @ValueSource(strings = {
        "*/SiteGlobals/Functions/RSSFeed/", // nennt rss und feed
        "/SiteGlobals/Functions/RSSFeed/DE/Pressemitteilungen/RSSPressemitteilungen.xml", // der Endpunkt
        "/", // Total-Bann
    })
    void conditionC_targetedDisallowIsNeverCarriedByAnInvitation(String muster) {
        // Wenn die Seite den Feed MEINT, hilft keine Einladung. Ein Total-Bann schon gar nicht.
        fetcher.respond(BMF_ROBOTS, 200, "User-agent: *\nDisallow: " + muster + "\n");
        fetcher.respond(BMF_TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("(c)");
    }

    @Test
    void conditionD_aDisallowInOurOwnNamedGroupIsADeliberateRefusal() {
        // Nennt uns die Seite beim Namen und sperrt uns, ist das keine Namenslücke — sondern eine
        // gezielte Absage. Die Einladung trägt sie nicht.
        fetcher.respond(BMF_ROBOTS, 200, """
                User-agent: *
                Allow: /

                User-agent: LucorisNewsBot
                Disallow: */SiteGlobals
                """);
        fetcher.respond(BMF_TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("(d)").contains("SPEZIFISCHE");
    }

    @Test
    void conditionD_aNamedAiReservationWinsOverAnInvitation() {
        // Der Reihenfolge-Test: die KI-Prüfung läuft VOR der Einladungs-Leiter. Schöbe jemand sie
        // dahinter, kippte die konservative Regel still.
        fetcher.respond(BMF_ROBOTS, 200,
                fixture("robots/bmf-robots.txt") + "\nUser-agent: GPTBot\nDisallow: /\n");
        fetcher.respond(BMF_TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("gptbot").contains("konservative Regel");
    }

    @Test
    void conditionE_aTdmReservationWinsOverAnInvitation() {
        // TDM gewinnt IMMER. Auch das ist ein Reihenfolge-Test.
        fetcher.respond(BMF_ROBOTS, 200, fixture("robots/bmf-robots.txt"));
        fetcher.respond(BMF_TDMREP, 200, """
                [ { "location": "/", "tdm-reservation": 1, "tdm-policy": "https://bmf.example/tdm" } ]
                """);

        RobotsGate.Decision decision = gate().check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("TDM-Vorbehalt");
    }

    @Test
    void anInvitationDoesNotHealAnUnreachableRobotsTxt() {
        // Fail-closed bleibt fail-closed: die Einladung sagt etwas über den Feed, nichts darüber,
        // ob wir die Hausordnung lesen konnten.
        fetcher.respond(BMF_ROBOTS, 503, "");
        fetcher.respond(BMF_TDMREP, 404, "");

        RobotsGate.Decision decision = gate().check(invited(BMF_FEED));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
        assertThat(decision.reason()).contains("fail-closed");
    }

    @Test
    void anAlreadyAllowedSourceNeverEntersTheInvitationPath() {
        // Destatis: dieselbe Pauschalsperre wie BMF, aber MIT der Allow-Zeile für den RSS-Zweig.
        // Das Ergebnis muss ALLOWED sein, nicht ALLOW_BY_INVITATION — sonst behaupteten wir eine
        // Ausnahme, wo gar keine nötig ist.
        URI destatisFeed = URI.create(
                "https://destatis.example/SiteGlobals/Functions/RSSFeed/DE/RSSNewsfeed/Aktuell.xml?nn=241288");
        fetcher.respond(URI.create("https://destatis.example/robots.txt"), 200,
                fixture("robots/destatis-robots.txt"));
        fetcher.respond(URI.create("https://destatis.example/.well-known/tdmrep.json"), 404, "");

        RobotsGate.Decision decision = gate().check(invited(destatisFeed));

        assertThat(decision.verdict()).isEqualTo(RobotsGate.Verdict.ALLOWED);
        assertThat(decision.evidence()).isNull();
    }

    @Test
    void twoSourcesOnOneHostShareTheRobotsFetchButNotTheVerdict() {
        // Die Einladung wird pro Aufruf ausgewertet, die Host-Policy nur einmal geholt.
        bmfLage();
        RobotsGate gate = gate();

        RobotsGate.Decision mitEinladung = gate.check(invited(BMF_FEED));
        RobotsGate.Decision ohneEinladung = gate.check(intent(BMF_FEED));

        assertThat(fetcher.callsTo(BMF_ROBOTS)).isEqualTo(1);
        assertThat(mitEinladung.verdict()).isEqualTo(RobotsGate.Verdict.ALLOW_BY_INVITATION);
        assertThat(ohneEinladung.verdict()).isEqualTo(RobotsGate.Verdict.BLOCKED);
    }

    private static String fixture(String resource) {
        try (InputStream in = CachingRobotsGateTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("Fixture fehlt im Test-Classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fake: liefert vorgegebene Antworten und zählt die Abrufe je URL. */
    private static final class FakePolicyFetcher implements PolicyFetcher {

        private final Map<URI, Response> responses = new HashMap<>();
        private final List<URI> calls = new ArrayList<>();

        void respond(URI url, int status, String body) {
            responses.put(url, new Response(status, body));
        }

        long callsTo(URI url) {
            return calls.stream().filter(url::equals).count();
        }

        @Override
        public Response get(URI url) {
            calls.add(url);
            // Nicht konfiguriert = so, als gäbe es das Dokument nicht.
            return responses.getOrDefault(url, new Response(404, ""));
        }
    }
}
