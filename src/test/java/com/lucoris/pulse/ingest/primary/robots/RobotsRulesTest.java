package com.lucoris.pulse.ingest.primary.robots;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Reiner Unit-Test des robots.txt-Parsers — ohne Spring, Netz oder DB. */
class RobotsRulesTest {

    /** Der Feed-Pfad, um den es beim BMF-Fall geht (6 Segmente, liegt im gesperrten Zweig). */
    private static final String BMF_FEED_PATH =
            "/SiteGlobals/Functions/RSSFeed/DE/Pressemitteilungen/RSSPressemitteilungen.xml";

    /** Byte-genaue Aufzeichnung der echten robots.txt — der Realfall, nicht eine Idealwelt. */
    private static String bmfRobotsTxt() {
        return fixture("robots/bmf-robots.txt");
    }

    private static String fixture(String resource) {
        try (InputStream in = RobotsRulesTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("Fixture fehlt im Test-Classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void noRobotsTxtMeansNoRestriction() {
        assertThat(RobotsRules.unrestricted().allows("LucorisNewsBot", "/rss/press.xml")).isTrue();
        assertThat(RobotsRules.parse("").allows("LucorisNewsBot", "/irgendwas")).isTrue();
    }

    @Test
    void wildcardGroupAppliesWhenOurAgentHasNoOwnGroup() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /intern/
                """);

        assertThat(rules.allows("LucorisNewsBot", "/rss/press.xml")).isTrue();
        assertThat(rules.allows("LucorisNewsBot", "/intern/geheim.html")).isFalse();
    }

    @Test
    void ourOwnGroupBeatsTheWildcardGroup() {
        // Eine eigene Gruppe für uns ersetzt die *-Gruppe vollständig (RFC 9309) — sie ergänzt sie nicht.
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /

                User-agent: LucorisNewsBot
                Disallow: /intern/
                """);

        assertThat(rules.allows("LucorisNewsBot", "/rss/press.xml")).isTrue();
        assertThat(rules.allows("LucorisNewsBot", "/intern/x")).isFalse();
        assertThat(rules.allows("IrgendeinAndererBot", "/rss/press.xml")).isFalse(); // fällt auf *
    }

    @Test
    void longestMatchWinsAndAllowBeatsDisallowOnEqualLength() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /daten/
                Allow: /daten/oeffentlich/
                """);

        assertThat(rules.allows("LucorisNewsBot", "/daten/intern.csv")).isFalse();
        // Längeres Allow gewinnt gegen kürzeres Disallow.
        assertThat(rules.allows("LucorisNewsBot", "/daten/oeffentlich/a.csv")).isTrue();
    }

    @Test
    void equalLengthTieGoesToAllow() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /feed
                Allow: /feed
                """);

        assertThat(rules.allows("LucorisNewsBot", "/feed")).isTrue();
    }

    @ParameterizedTest(name = "[{index}] Muster {0} vs. Pfad {1} -> erlaubt={2}")
    @CsvSource({
        // Wildcard '*'
        "'/*.pdf$',        /doc/bericht.pdf,   false",
        "'/*.pdf$',        /doc/bericht.html,  true",
        // End-Anker '$'
        "'/feed$',         /feed,              false",
        "'/feed$',         /feed/atom.xml,     true",
        // Präfix-Match ohne Anker
        "'/feed',          /feed/atom.xml,     false",
        "'/private',       /privat,            true",
    })
    void wildcardsAndEndAnchorsAreHonoured(String muster, String pfad, boolean erlaubt) {
        RobotsRules rules = RobotsRules.parse("User-agent: *\nDisallow: " + muster + "\n");

        assertThat(rules.allows("LucorisNewsBot", pfad)).isEqualTo(erlaubt);
    }

    @Test
    void emptyDisallowMeansExplicitlyAllowed() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow:
                """);

        assertThat(rules.allows("LucorisNewsBot", "/alles")).isTrue();
    }

    @Test
    void commentsAndUnknownDirectivesAreIgnored() {
        RobotsRules rules = RobotsRules.parse("""
                # Kommentarzeile
                Sitemap: https://example.org/sitemap.xml
                Crawl-delay: 10
                User-agent: *   # auch hier ein Kommentar
                Disallow: /intern/
                """);

        assertThat(rules.allows("LucorisNewsBot", "/intern/x")).isFalse();
        assertThat(rules.allows("LucorisNewsBot", "/rss")).isTrue();
    }

    @Test
    void agentMatchingIsCaseInsensitive() {
        RobotsRules rules = RobotsRules.parse("""
                User-Agent: LUCORISNEWSBOT
                Disallow: /
                """);

        assertThat(rules.allows("LucorisNewsBot", "/rss")).isFalse();
    }

    // --- Die konservative Regel: KI-Vorbehalt zählt, auch wenn wir nicht genannt sind ---

    @Test
    void aiCrawlerBlockCountsAsReservationEvenWhenOurBotIsAllowed() {
        // GENAU der Fall, um den es geht: uns erlaubt die Seite alles, aber sie sperrt GPTBot.
        // Das ist ein erkennbarer KI-/TDM-Vorbehalt -> die Namenslücke nutzen wir NICHT aus.
        RobotsRules rules = RobotsRules.parse("""
                User-agent: GPTBot
                Disallow: /

                User-agent: CCBot
                Disallow: /

                User-agent: *
                Allow: /
                """);

        assertThat(rules.allows("LucorisNewsBot", "/rss/press.xml")).isTrue(); // uns erlaubt ...
        assertThat(rules.blockedAiCrawlers("/rss/press.xml"))
                .containsExactlyInAnyOrder("gptbot", "ccbot"); // ... aber Vorbehalt erkannt
    }

    @Test
    void aiCrawlerBlockOnAnUnrelatedPathDoesNotReserveOurPath() {
        // Kein Fehlalarm: GPTBot ist nur für /shop gesperrt, wir holen /rss.
        RobotsRules rules = RobotsRules.parse("""
                User-agent: GPTBot
                Disallow: /shop/

                User-agent: *
                Allow: /
                """);

        assertThat(rules.blockedAiCrawlers("/rss/press.xml")).isEmpty();
        assertThat(rules.blockedAiCrawlers("/shop/artikel")).containsExactly("gptbot");
    }

    @Test
    void siteWithoutAnyAiReservationYieldsNoBlockedCrawlers() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: /intern/
                """);

        assertThat(rules.blockedAiCrawlers("/rss/press.xml")).isEmpty();
    }

    /**
     * Echter Fall (bundesfinanzministerium.de, 2026-07-14): ein Wildcard-Disallow auf den
     * SiteGlobals-Zweig sperrt auch den RSS-Feed, der dort drin liegt. Die Allow-Ausnahmen gelten
     * nur für Buttons, JavaScript, SocialBookmarks und CSS — NICHT für
     * {@code /SiteGlobals/Functions/RSSFeed/}.
     *
     * <p>Eine Handprüfung hatte die Quelle als „Feed-Pfade nicht disallowed" freigegeben. Genau
     * diese Art von Irrtum soll das Gate abfangen — deshalb steht der Fall hier fest.
     */
    @Test
    void bmfPatternBlocksTheRssFeedDespiteNarrowerAllowExceptions() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Disallow: */SiteGlobals
                Disallow: */SharedDocs/ExterneLinks
                Allow: */SiteGlobals/Forms/_components/Buttons/
                Allow: */SiteGlobals/Functions/JavaScript/
                Allow: */SiteGlobals/Functions/SocialBookmarks/
                Allow: */SiteGlobals/StyleBundles/CSS/
                Crawl-delay: 180
                """);

        assertThat(rules.allows("LucorisNewsBot",
                "/SiteGlobals/Functions/RSSFeed/DE/Pressemitteilungen/RSSPressemitteilungen.xml"))
                .isFalse();

        // Die ausdrücklich erlaubten Zweige bleiben erlaubt (längeres Allow gewinnt).
        assertThat(rules.allows("LucorisNewsBot", "/SiteGlobals/Functions/JavaScript/app.js")).isTrue();
        // Und alles außerhalb von /SiteGlobals sowieso.
        assertThat(rules.allows("LucorisNewsBot", "/DE/Presse/Pressemitteilungen/mitteilung.html")).isTrue();
    }

    // --- match(): die GEWINNENDE Regel, nicht nur ein boolean ---

    @Test
    void matchExposesTheWinningPatternAndItsUserAgentGroup() {
        // Genau dieses Tripel braucht die Einladungs-Prüfung: Muster (c) und UA-Gruppe (d).
        RobotsRules rules = RobotsRules.parse(bmfRobotsTxt());

        RobotsRules.Match match = rules.match("LucorisNewsBot", BMF_FEED_PATH).orElseThrow();

        assertThat(match.allow()).isFalse();
        assertThat(match.pattern()).isEqualTo("*/SiteGlobals");
        assertThat(match.group()).isEqualTo("*");
    }

    @Test
    void matchReportsOurOwnGroupWhenTheSiteNamesUs() {
        RobotsRules rules = RobotsRules.parse("""
                User-agent: *
                Allow: /

                User-agent: LucorisNewsBot
                Disallow: /intern/
                """);

        assertThat(rules.match("LucorisNewsBot", "/intern/x").orElseThrow().group())
                .isEqualTo("lucorisnewsbot");
        assertThat(rules.namesAgent("LucorisNewsBot")).isTrue();
        assertThat(rules.namesAgent("GPTBot")).isFalse();
    }

    @Test
    void noMatchingRuleMeansAllowed() {
        RobotsRules rules = RobotsRules.parse("User-agent: *\nDisallow: /intern/\n");

        assertThat(rules.match("LucorisNewsBot", "/rss/press.xml")).isEmpty();
        assertThat(rules.allows("LucorisNewsBot", "/rss/press.xml")).isTrue();
    }

    // --- Die zwei Wächter für den blockedAiCrawlers-Fix (siehe ADR 24) ---

    @Test
    void wildcardDisallowIsNotAnAiReservation() {
        // DER zentrale Regressionstest. Die BMF-robots.txt nennt KEINEN einzigen KI-Crawler; sie hat
        // nur ein generisches Disallow in der '*'-Gruppe. Vor dem Fix meldete blockedAiCrawlers hier
        // ALLE 20 Crawler als gesperrt (weil sie auf '*' zurückfielen) — eine Falschaussage, die
        // jede Einladungs-Prüfung vorher erschlagen hätte.
        RobotsRules rules = RobotsRules.parse(bmfRobotsTxt());

        assertThat(rules.blockedAiCrawlers(BMF_FEED_PATH)).isEmpty();

        // Gesperrt sind wir trotzdem — nur eben durch die Hausordnung, nicht durch einen KI-Vorbehalt.
        assertThat(rules.allows("LucorisNewsBot", BMF_FEED_PATH)).isFalse();
    }

    @Test
    void siteWideDisallowStillBlocksUs() {
        // Der zweite Wächter: die Lockerung öffnet NICHTS. Eine Seite, die alle Bots aussperrt,
        // bleibt gesperrt — über allows(), nicht über die KI-Regel.
        RobotsRules rules = RobotsRules.parse("User-agent: *\nDisallow: /\n");

        assertThat(rules.blockedAiCrawlers("/rss/press.xml")).isEmpty(); // kein KI-Vorbehalt ...
        assertThat(rules.allows("LucorisNewsBot", "/rss/press.xml")).isFalse(); // ... aber verboten
    }

    @Test
    void namedAiGroupOnTopOfAGenericDisallowIsStillAReservation() {
        // Ergänzt die BMF-Datei um eine benannte GPTBot-Gruppe: jetzt IST es ein KI-Vorbehalt.
        RobotsRules rules = RobotsRules.parse(bmfRobotsTxt() + "\nUser-agent: GPTBot\nDisallow: /\n");

        assertThat(rules.blockedAiCrawlers(BMF_FEED_PATH)).containsExactly("gptbot");
    }

    // --- Der Realfall aus der echten Datei ---

    @Test
    void destatisAllowsTheRssBranchExplicitlyWhereBmfForgotIt() {
        // Dieselbe Pauschalsperre wie bei BMF, aber mit der Zeile, die BMF fehlt.
        RobotsRules destatis = RobotsRules.parse(fixture("robots/destatis-robots.txt"));

        assertThat(destatis.allows("LucorisNewsBot",
                "/SiteGlobals/Functions/RSSFeed/DE/RSSNewsfeed/Aktuell.xml?nn=241288")).isTrue();
        assertThat(destatis.blockedAiCrawlers("/SiteGlobals/Functions/RSSFeed/DE/RSSNewsfeed/Aktuell.xml"))
                .isEmpty();
        // Der übrige SiteGlobals-Zweig bleibt gesperrt.
        assertThat(destatis.allows("LucorisNewsBot", "/SiteGlobals/Forms/Suche/irgendwas")).isFalse();
    }

    @Test
    void crawlDelayIsParsedForTheGroupThatAppliesToUs() {
        // BMF setzt 180 Sekunden. Wer sich auf das Wohlwollen des Herausgebers beruft und zugleich
        // seine Abrufgrenze ignoriert, widerspricht sich selbst — der Validator warnt darum.
        RobotsRules bmf = RobotsRules.parse(bmfRobotsTxt());

        assertThat(bmf.crawlDelaySeconds("LucorisNewsBot")).contains(180);
    }

    @Test
    void withoutACrawlDelayThereIsNoLimitToHonour() {
        RobotsRules rules = RobotsRules.parse("User-agent: *\nDisallow: /intern/\n");

        assertThat(rules.crawlDelaySeconds("LucorisNewsBot")).isEmpty();
    }

    @Test
    void productTokenIsExtractedFromTheFullUserAgent() {
        assertThat(RobotsRules.productToken("LucorisNewsBot/1.0 (+https://lucoris.com/bot)"))
                .isEqualTo("LucorisNewsBot");
        assertThat(RobotsRules.productToken("LucorisNewsBot")).isEqualTo("LucorisNewsBot");
    }
}
